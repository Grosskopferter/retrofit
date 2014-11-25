/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package retrofit;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import retrofit.client.Response;
import retrofit.http.Body;
import retrofit.http.Field;
import retrofit.http.FieldMap;
import retrofit.http.FormUrlEncoded;
import retrofit.http.Header;
import retrofit.http.Headers;
import retrofit.http.Multipart;
import retrofit.http.Part;
import retrofit.http.PartMap;
import retrofit.http.Path;
import retrofit.http.Query;
import retrofit.http.QueryMap;
import retrofit.http.RestMethod;
import retrofit.http.Streaming;
import rx.Observable;

/** Request metadata about a service interface declaration. */
public final class RestMethodInfo {

  private enum ResponseType {
    VOID,
    OBSERVABLE,
    OBJECT
  }

  // Upper and lower characters, digits, underscores, and hyphens, starting with a character.
  private static final String PARAM = "[a-zA-Z][a-zA-Z0-9_-]*";
  private static final Pattern PARAM_NAME_REGEX = Pattern.compile(PARAM);
  private static final Pattern PARAM_URL_REGEX = Pattern.compile("\\{(" + PARAM + ")\\}");

  enum RequestType {
    /** No content-specific logic required. */
    SIMPLE,
    /** Multi-part request body. */
    MULTIPART,
    /** Form URL-encoded request body. */
    FORM_URL_ENCODED
  }

  final Method method;

  boolean loaded = false;

  // Method-level details
  final ResponseType responseType;
  final boolean isSynchronous;
  final boolean isObservable;
  Type responseObjectType;
  RequestType requestType = RequestType.SIMPLE;
  String requestMethod;
  boolean requestHasBody;
  private String requestUrl;
  Set<String> requestUrlParamNames;
  String requestQuery;
  List<retrofit.client.Header> headers;
  String contentTypeHeader;
  boolean isStreaming;

  // Parameter-level details
  Annotation[] requestParamAnnotations;

  RestMethodInfo(Method method) {
    this.method = method;
    this.responseType = this.parseResponseType();
    this.isSynchronous = (this.responseType == ResponseType.OBJECT);
    this.isObservable = (this.responseType == ResponseType.OBSERVABLE);
  }

  private RuntimeException methodError(String message, Object... args) {
    if (args.length > 0) {
      message = String.format(message, args);
    }
    return new IllegalArgumentException(
        this.method.getDeclaringClass().getSimpleName() + "." + this.method.getName() + ": " + message);
  }

  private RuntimeException parameterError(int index, String message, Object... args) {
    return this.methodError(message + " (parameter #" + (index + 1) + ")", args);
  }

  synchronized void init() {
    if (this.loaded) {
		return;
	}

    this.parseMethodAnnotations();
    this.parseParameters();

    this.loaded = true;
  }

  /** Loads {@link #requestMethod} and {@link #requestType}. */
  private void parseMethodAnnotations() {
    for (Annotation methodAnnotation : this.method.getAnnotations()) {
      Class<? extends Annotation> annotationType = methodAnnotation.annotationType();
      RestMethod methodInfo = null;

      // Look for a @RestMethod annotation on the parameter annotation indicating request method.
      for (Annotation innerAnnotation : annotationType.getAnnotations()) {
        if (RestMethod.class == innerAnnotation.annotationType()) {
          methodInfo = (RestMethod) innerAnnotation;
          break;
        }
      }

      if (methodInfo != null) {
        if (this.requestMethod != null) {
          throw this.methodError("Only one HTTP method is allowed. Found: %s and %s.", this.requestMethod,
              methodInfo.value());
        }
        String path;
        try {
          path = (String) annotationType.getMethod("value").invoke(methodAnnotation);
        } catch (Exception e) {
          throw this.methodError("Failed to extract String 'value' from @%s annotation.",
              annotationType.getSimpleName());
        }
        this.parsePath(path);
        this.requestMethod = methodInfo.value();
        this.requestHasBody = methodInfo.hasBody();
      } else if (annotationType == Headers.class) {
        String[] headersToParse = ((Headers) methodAnnotation).value();
        if (headersToParse.length == 0) {
          throw this.methodError("@Headers annotation is empty.");
        }
        this.headers = this.parseHeaders(headersToParse);
      } else if (annotationType == Multipart.class) {
        if (this.requestType != RequestType.SIMPLE) {
          throw this.methodError("Only one encoding annotation is allowed.");
        }
        this.requestType = RequestType.MULTIPART;
      } else if (annotationType == FormUrlEncoded.class) {
        if (this.requestType != RequestType.SIMPLE) {
          throw this.methodError("Only one encoding annotation is allowed.");
        }
        this.requestType = RequestType.FORM_URL_ENCODED;
      } else if (annotationType == Streaming.class) {
        if (this.responseObjectType != Response.class) {
          throw this.methodError(
              "Only methods having %s as data type are allowed to have @%s annotation.",
              Response.class.getSimpleName(), Streaming.class.getSimpleName());
        }
        this.isStreaming = true;
      }
    }

    if (this.requestMethod == null) {
      throw this.methodError("HTTP method annotation is required (e.g., @GET, @POST, etc.).");
    }
    if (!this.requestHasBody) {
      if (this.requestType == RequestType.MULTIPART) {
        throw this.methodError(
            "Multipart can only be specified on HTTP methods with request body (e.g., @POST).");
      }
      if (this.requestType == RequestType.FORM_URL_ENCODED) {
        throw this.methodError("FormUrlEncoded can only be specified on HTTP methods with request body "
                + "(e.g., @POST).");
      }
    }
  }

  /** Loads {@link #requestUrl}, {@link #requestUrlParamNames}, and {@link #requestQuery}. */
  private void parsePath(String path) {
    if (path == null || path.length() == 0 || path.charAt(0) != '/') {
      throw this.methodError("URL path \"%s\" must start with '/'.", path);
    }

    // Get the relative URL path and existing query string, if present.
    String url = path;
    String query = null;
    int question = path.indexOf('?');
    if (question != -1 && question < path.length() - 1) {
      url = path.substring(0, question);
      query = path.substring(question + 1);

      // Ensure the query string does not have any named parameters.
      Matcher queryParamMatcher = PARAM_URL_REGEX.matcher(query);
      if (queryParamMatcher.find()) {
        throw this.methodError("URL query string \"%s\" must not have replace block. For dynamic query"
            + " parameters use @Query.", query);
      }
    }

    Set<String> urlParams = parsePathParameters(path);

    this.setRequestUrl(url);
    this.requestUrlParamNames = urlParams;
    this.requestQuery = query;
  }

  List<retrofit.client.Header> parseHeaders(String[] headers) {
    List<retrofit.client.Header> headerList = new ArrayList<retrofit.client.Header>();
    for (String header : headers) {
      int colon = header.indexOf(':');
      if (colon == -1 || colon == 0 || colon == header.length() - 1) {
        throw this.methodError("@Headers value must be in the form \"Name: Value\". Found: \"%s\"",
            header);
      }
      String headerName = header.substring(0, colon);
      String headerValue = header.substring(colon + 1).trim();
      if ("Content-Type".equalsIgnoreCase(headerName)) {
        this.contentTypeHeader = headerValue;
      } else {
        headerList.add(new retrofit.client.Header(headerName, headerValue));
      }
    }
    return headerList;
  }

  /** Loads {@link #responseObjectType}. */
  private ResponseType parseResponseType() {
    // Synchronous methods have a non-void return type.
    // Observable methods have a return type of Observable.
    Type returnType = this.method.getGenericReturnType();

    // Asynchronous methods should have a Callback type as the last argument.
    Type lastArgType = null;
    Class<?> lastArgClass = null;
    Type[] parameterTypes = this.method.getGenericParameterTypes();
    if (parameterTypes.length > 0) {
      Type typeToCheck = parameterTypes[parameterTypes.length - 1];
      lastArgType = typeToCheck;
      if (typeToCheck instanceof ParameterizedType) {
        typeToCheck = ((ParameterizedType) typeToCheck).getRawType();
      }
      if (typeToCheck instanceof Class) {
        lastArgClass = (Class<?>) typeToCheck;
      }
    }

    boolean hasReturnType = returnType != void.class;
    boolean hasCallback = lastArgClass != null && Callback.class.isAssignableFrom(lastArgClass);

    // Check for invalid configurations.
    if (hasReturnType && hasCallback) {
      throw this.methodError("Must have return type or Callback as last argument, not both.");
    }
    if (!hasReturnType && !hasCallback) {
      throw this.methodError("Must have either a return type or Callback as last argument.");
    }

    if (hasReturnType) {
      if (Platform.HAS_RX_JAVA) {
        Class rawReturnType = Types.getRawType(returnType);
        if (RxSupport.isObservable(rawReturnType)) {
          returnType = RxSupport.getObservableType(returnType, rawReturnType);
          this.responseObjectType = getParameterUpperBound((ParameterizedType) returnType);
          return ResponseType.OBSERVABLE;
        }
      }
      this.responseObjectType = returnType;
      return ResponseType.OBJECT;
    }

    lastArgType = Types.getSupertype(lastArgType, Types.getRawType(lastArgType), Callback.class);
    if (lastArgType instanceof ParameterizedType) {
      this.responseObjectType = getParameterUpperBound((ParameterizedType) lastArgType);
      return ResponseType.VOID;
    }

    throw this.methodError("Last parameter must be of type Callback<X> or Callback<? super X>.");
  }

  private static Type getParameterUpperBound(ParameterizedType type) {
    Type[] types = type.getActualTypeArguments();
    for (int i = 0; i < types.length; i++) {
      Type paramType = types[i];
      if (paramType instanceof WildcardType) {
        types[i] = ((WildcardType) paramType).getUpperBounds()[0];
      }
    }
    return types[0];
  }

  /**
   * Loads {@link #requestParamAnnotations}. Must be called after {@link #parseMethodAnnotations()}.
   */
  private void parseParameters() {
    Class<?>[] methodParameterTypes = this.method.getParameterTypes();

    Annotation[][] methodParameterAnnotationArrays = this.method.getParameterAnnotations();
    int count = methodParameterAnnotationArrays.length;
    if (!this.isSynchronous && !this.isObservable) {
      count -= 1; // Callback is last argument when not a synchronous method.
    }

    Annotation[] requestParamAnnotations = new Annotation[count];

    boolean gotField = false;
    boolean gotPart = false;
    boolean gotBody = false;

    for (int i = 0; i < count; i++) {
      Class<?> methodParameterType = methodParameterTypes[i];
      Annotation[] methodParameterAnnotations = methodParameterAnnotationArrays[i];
      if (methodParameterAnnotations != null) {
        for (Annotation methodParameterAnnotation : methodParameterAnnotations) {
          Class<? extends Annotation> methodAnnotationType =
              methodParameterAnnotation.annotationType();

          if (methodAnnotationType == Path.class) {
            String name = ((Path) methodParameterAnnotation).value();
            this.validatePathName(i, name);
          } else if (methodAnnotationType == Query.class) {
            // Nothing to do.
          } else if (methodAnnotationType == QueryMap.class) {
            if (!Map.class.isAssignableFrom(methodParameterType)) {
              throw this.parameterError(i, "@QueryMap parameter type must be Map.");
            }
          } else if (methodAnnotationType == Header.class) {
            // Nothing to do.
          } else if (methodAnnotationType == Field.class) {
            if (this.requestType != RequestType.FORM_URL_ENCODED) {
              throw this.parameterError(i, "@Field parameters can only be used with form encoding.");
            }

            gotField = true;
          } else if (methodAnnotationType == FieldMap.class) {
            if (this.requestType != RequestType.FORM_URL_ENCODED) {
              throw this.parameterError(i, "@FieldMap parameters can only be used with form encoding.");
            }
            if (!Map.class.isAssignableFrom(methodParameterType)) {
              throw this.parameterError(i, "@FieldMap parameter type must be Map.");
            }

            gotField = true;
          } else if (methodAnnotationType == Part.class) {
            if (this.requestType != RequestType.MULTIPART) {
              throw this.parameterError(i, "@Part parameters can only be used with multipart encoding.");
            }

            gotPart = true;
          } else if (methodAnnotationType == PartMap.class) {
            if (this.requestType != RequestType.MULTIPART) {
              throw this.parameterError(i,
                  "@PartMap parameters can only be used with multipart encoding.");
            }
            if (!Map.class.isAssignableFrom(methodParameterType)) {
              throw this.parameterError(i, "@PartMap parameter type must be Map.");
            }

            gotPart = true;
          } else if (methodAnnotationType == Body.class) {
            if (this.requestType != RequestType.SIMPLE) {
              throw this.parameterError(i,
                  "@Body parameters cannot be used with form or multi-part encoding.");
            }
            if (gotBody) {
              throw this.methodError("Multiple @Body method annotations found.");
            }

            gotBody = true;
          } else {
            // This is a non-Retrofit annotation. Skip to the next one.
            continue;
          }

          if (requestParamAnnotations[i] != null) {
            throw this.parameterError(i,
                "Multiple Retrofit annotations found, only one allowed: @%s, @%s.",
                requestParamAnnotations[i].annotationType().getSimpleName(),
                methodAnnotationType.getSimpleName());
          }
          requestParamAnnotations[i] = methodParameterAnnotation;
        }
      }

      if (requestParamAnnotations[i] == null) {
        throw this.parameterError(i, "No Retrofit annotation found.");
      }
    }

    if (this.requestType == RequestType.SIMPLE && !this.requestHasBody && gotBody) {
      throw this.methodError("Non-body HTTP method cannot contain @Body or @TypedOutput.");
    }
    if (this.requestType == RequestType.FORM_URL_ENCODED && !gotField) {
      throw this.methodError("Form-encoded method must contain at least one @Field.");
    }
    if (this.requestType == RequestType.MULTIPART && !gotPart) {
      throw this.methodError("Multipart method must contain at least one @Part.");
    }

    this.requestParamAnnotations = requestParamAnnotations;
  }

  private void validatePathName(int index, String name) {
    if (!PARAM_NAME_REGEX.matcher(name).matches()) {
      throw this.parameterError(index, "@Path parameter name must match %s. Found: %s",
          PARAM_URL_REGEX.pattern(), name);
    }
    // Verify URL replacement name is actually present in the URL path.
    if (!this.requestUrlParamNames.contains(name)) {
      throw this.parameterError(index, "URL \"%s\" does not contain \"{%s}\".", this.getRequestUrl(), name);
    }
  }

  /**
   * Gets the set of unique path parameters used in the given URI. If a parameter is used twice
   * in the URI, it will only show up once in the set.
   */
  static Set<String> parsePathParameters(String path) {
    Matcher m = PARAM_URL_REGEX.matcher(path);
    Set<String> patterns = new LinkedHashSet<String>();
    while (m.find()) {
      patterns.add(m.group(1));
    }
    return patterns;
  }

  /**
 * @return the requestUrl
 */
public String getRequestUrl() {
	return requestUrl;
}

/**
 * @param requestUrl the requestUrl to set
 */
public void setRequestUrl(String requestUrl) {
	this.requestUrl = requestUrl;
}

/** Indirection to avoid log complaints if RxJava isn't present. */
  private static final class RxSupport {
    public static boolean isObservable(Class rawType) {
      return rawType == Observable.class;
    }

    public static Type getObservableType(Type contextType, Class contextRawType) {
      return Types.getSupertype(contextType, contextRawType, Observable.class);
    }
  }
}
