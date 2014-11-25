package retrofit;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import retrofit.RequestInterceptor.RequestFacade;
import retrofit.RxSupport.Invoker;
import retrofit.client.Header;
import retrofit.client.Response;
import retrofit.mime.TypedInput;
import rx.Observer;
import rx.Subscription;
import rx.schedulers.Schedulers;
import rx.schedulers.TestScheduler;

public class RxSupportTest {

  private Object response;
  private ResponseWrapper responseWrapper;
  private Invoker invoker = spy(new Invoker() {
    @Override public ResponseWrapper invoke(RequestInterceptor requestInterceptor) {
      return RxSupportTest.this.responseWrapper;
    }
  });
  private RequestInterceptor requestInterceptor = spy(new RequestInterceptor() {
    @Override public void intercept(RequestFacade request, RestMethodInfo methodInfo) {
    }
  });

  private QueuedSynchronousExecutor executor;
  private RxSupport rxSupport;

  @Mock Observer<Object> subscriber;

  @Before public void setUp() {
    MockitoAnnotations.initMocks(this);
    this.response = new Object();
    this.responseWrapper = new ResponseWrapper(
            new Response(
                    "http://example.com", 200, "Success",
                    Collections.<Header>emptyList(), mock(TypedInput.class)
            ), this.response
    );
    this.executor = spy(new QueuedSynchronousExecutor());
    this.rxSupport = new RxSupport(this.executor, ErrorHandler.DEFAULT, this.requestInterceptor);
  }

  @Test public void observableCallsOnNextOnHttpExecutor() {
    this.rxSupport.createRequestObservable(this.invoker).subscribe(this.subscriber);
    this.executor.executeNextInQueue();
    verify(this.subscriber, times(1)).onNext(this.response);
  }

  @Test public void observableCallsOnNextOnHttpExecutorWithSubscriber() {
    TestScheduler test = Schedulers.test();
    this.rxSupport.createRequestObservable(this.invoker).subscribeOn(test).subscribe(this.subscriber);
    // Subscription is handled via the Scheduler.
    test.triggerActions();
    // This will only execute up to the executor in OnSubscribe.
    verify(this.subscriber, never()).onNext(any());
    // Upon continuing the executor we then run the retrofit request.
    this.executor.executeNextInQueue();
    verify(this.subscriber, times(1)).onNext(this.response);
  }

  @Test public void observableUnSubscribesDoesNotExecuteCallable() throws Exception {
    Subscription subscription = this.rxSupport.createRequestObservable(this.invoker).subscribe(this.subscriber);
    verify(this.subscriber, never()).onNext(any());

    // UnSubscribe here should cancel the queued runnable.
    subscription.unsubscribe();

    this.executor.executeNextInQueue();
    verify(this.invoker, never()).invoke(any(RequestInterceptor.class));
    verify(this.subscriber, never()).onNext(this.response);
  }

  @Test public void observableCallsOperatorsOffHttpExecutor() {
    TestScheduler test = Schedulers.test();
    this.rxSupport.createRequestObservable(this.invoker)
            .delaySubscription(1000, TimeUnit.MILLISECONDS, test)
            .subscribe(this.subscriber);

    verify(this.subscriber, never()).onNext(any());
    test.advanceTimeBy(1000, TimeUnit.MILLISECONDS);
    // Upon continuing the executor we then run the retrofit request.
    this.executor.executeNextInQueue();
    verify(this.subscriber, times(1)).onNext(this.response);
  }

  @Test public void observableDoesNotLockExecutor() {
    TestScheduler test = Schedulers.test();
    this.rxSupport.createRequestObservable(this.invoker)
            .delay(1000, TimeUnit.MILLISECONDS, test)
            .subscribe(this.subscriber);

    this.rxSupport.createRequestObservable(this.invoker)
            .delay(2000, TimeUnit.MILLISECONDS, test)
            .subscribe(this.subscriber);

    // Nothing fired yet
    verify(this.subscriber, never()).onNext(any());
    // Subscriptions should of been queued up and executed even tho we delayed on the Subscriber.
    this.executor.executeNextInQueue();
    this.executor.executeNextInQueue();

    verify(this.subscriber, never()).onNext(this.response);

    test.advanceTimeBy(1000, TimeUnit.MILLISECONDS);
    verify(this.subscriber, times(1)).onNext(this.response);

    test.advanceTimeBy(1000, TimeUnit.MILLISECONDS);
    verify(this.subscriber, times(2)).onNext(this.response);
  }

  @Test public void observableRespectsObserveOn() throws Exception {
    TestScheduler observe = Schedulers.test();
    this.rxSupport.createRequestObservable(this.invoker)
            .observeOn(observe)
            .subscribe(this.subscriber);

    verify(this.subscriber, never()).onNext(any());
    this.executor.executeNextInQueue();

    // Should have no response yet, but callback should of been executed.
    verify(this.subscriber, never()).onNext(any());
    verify(this.invoker, times(1)).invoke(any(RequestInterceptor.class));

    // Forward the Observable Scheduler
    observe.triggerActions();
    verify(this.subscriber, times(1)).onNext(this.response);
  }

  @Test public void observableCallsInterceptorForEverySubscription() throws Exception {
    this.rxSupport.createRequestObservable(this.invoker).subscribe(this.subscriber);
    this.rxSupport.createRequestObservable(this.invoker).subscribe(this.subscriber);

    // The interceptor should have been called for each request upon subscription.
    verify(this.requestInterceptor, times(2)).intercept(any(RequestFacade.class), null);

    // Background execution of the requests should not touch the interceptor.
    this.executor.executeAll();
    verifyNoMoreInteractions(this.requestInterceptor);
  }

  /**
   * Test Executor to iterate through Executions to aid in checking
   * that the Observable implementation is correct.
   */
  static class QueuedSynchronousExecutor implements Executor {
    Deque<Runnable> runnableQueue = new ArrayDeque<Runnable>();

    @Override public void execute(Runnable runnable) {
      this.runnableQueue.add(runnable);
    }

    /**
     * Will throw exception if you are expecting something to be added to the Executor
     * and it hasn't.
     */
    void executeNextInQueue() {
      this.runnableQueue.removeFirst().run();
    }

    /**
     * Executes any queued executions on the executor.
     */
    void executeAll() {
      Iterator<Runnable> iterator = this.runnableQueue.iterator();
      while (iterator.hasNext()) {
        Runnable next = iterator.next();
        next.run();
        iterator.remove();
      }
    }
  }
}
