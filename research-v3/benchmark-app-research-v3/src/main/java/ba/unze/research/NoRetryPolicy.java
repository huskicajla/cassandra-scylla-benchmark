package ba.unze.research;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.context.DriverContext;
import com.datastax.oss.driver.api.core.retry.*;
import com.datastax.oss.driver.api.core.servererrors.*;
import com.datastax.oss.driver.api.core.session.Request;

public final class NoRetryPolicy implements RetryPolicy {
    public NoRetryPolicy(DriverContext context,String profileName) {}
    public RetryDecision onReadTimeout(Request r,ConsistencyLevel c,int b,int received,boolean data,int retries) {return RetryDecision.RETHROW;}
    public RetryDecision onWriteTimeout(Request r,ConsistencyLevel c,WriteType type,int b,int received,int retries) {return RetryDecision.RETHROW;}
    public RetryDecision onUnavailable(Request r,ConsistencyLevel c,int required,int alive,int retries) {return RetryDecision.RETHROW;}
    public RetryDecision onRequestAborted(Request r,Throwable e,int retries) {return RetryDecision.RETHROW;}
    public RetryDecision onErrorResponse(Request r,CoordinatorException e,int retries) {return RetryDecision.RETHROW;}
    public void close() {}
}
