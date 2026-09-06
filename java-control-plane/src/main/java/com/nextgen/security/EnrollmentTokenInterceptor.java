package com.nextgen.security;

import com.nextgen.controlplane.NodeEnrollmentServiceImpl;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;

/**
 * Moves the enrolment token from gRPC metadata onto the call context.
 *
 * <p>Exists so {@link NodeEnrollmentServiceImpl} can read the token without depending on gRPC
 * metadata plumbing, and so the token stays out of the request message where it would eventually be
 * printed by a proto {@code toString()} in a debug log.
 */
public class EnrollmentTokenInterceptor implements ServerInterceptor {

    @Override
    public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
            ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {

        String token = headers.get(NodeEnrollmentServiceImpl.TOKEN_HEADER);
        Context context = Context.current()
                .withValue(NodeEnrollmentServiceImpl.TOKEN_CONTEXT_KEY, token);
        return Contexts.interceptCall(context, call, headers, next);
    }
}
