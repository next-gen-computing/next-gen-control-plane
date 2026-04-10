package com.nextgen.controlplane.grpc;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@io.grpc.stub.annotations.GrpcGenerated
public final class NodeAgentServiceGrpc {

  private NodeAgentServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "com.nextgen.controlplane.grpc.NodeAgentService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.RegisterRequest,
      com.nextgen.controlplane.grpc.RegisterResponse> getRegisterNodeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "RegisterNode",
      requestType = com.nextgen.controlplane.grpc.RegisterRequest.class,
      responseType = com.nextgen.controlplane.grpc.RegisterResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.RegisterRequest,
      com.nextgen.controlplane.grpc.RegisterResponse> getRegisterNodeMethod() {
    io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.RegisterRequest, com.nextgen.controlplane.grpc.RegisterResponse> getRegisterNodeMethod;
    if ((getRegisterNodeMethod = NodeAgentServiceGrpc.getRegisterNodeMethod) == null) {
      synchronized (NodeAgentServiceGrpc.class) {
        if ((getRegisterNodeMethod = NodeAgentServiceGrpc.getRegisterNodeMethod) == null) {
          NodeAgentServiceGrpc.getRegisterNodeMethod = getRegisterNodeMethod =
              io.grpc.MethodDescriptor.<com.nextgen.controlplane.grpc.RegisterRequest, com.nextgen.controlplane.grpc.RegisterResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "RegisterNode"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.nextgen.controlplane.grpc.RegisterRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.nextgen.controlplane.grpc.RegisterResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeAgentServiceMethodDescriptorSupplier("RegisterNode"))
              .build();
        }
      }
    }
    return getRegisterNodeMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.HeartbeatRequest,
      com.nextgen.controlplane.grpc.HeartbeatResponse> getSendHeartbeatMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendHeartbeat",
      requestType = com.nextgen.controlplane.grpc.HeartbeatRequest.class,
      responseType = com.nextgen.controlplane.grpc.HeartbeatResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.HeartbeatRequest,
      com.nextgen.controlplane.grpc.HeartbeatResponse> getSendHeartbeatMethod() {
    io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.HeartbeatRequest, com.nextgen.controlplane.grpc.HeartbeatResponse> getSendHeartbeatMethod;
    if ((getSendHeartbeatMethod = NodeAgentServiceGrpc.getSendHeartbeatMethod) == null) {
      synchronized (NodeAgentServiceGrpc.class) {
        if ((getSendHeartbeatMethod = NodeAgentServiceGrpc.getSendHeartbeatMethod) == null) {
          NodeAgentServiceGrpc.getSendHeartbeatMethod = getSendHeartbeatMethod =
              io.grpc.MethodDescriptor.<com.nextgen.controlplane.grpc.HeartbeatRequest, com.nextgen.controlplane.grpc.HeartbeatResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendHeartbeat"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.nextgen.controlplane.grpc.HeartbeatRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.nextgen.controlplane.grpc.HeartbeatResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeAgentServiceMethodDescriptorSupplier("SendHeartbeat"))
              .build();
        }
      }
    }
    return getSendHeartbeatMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.DeregisterRequest,
      com.nextgen.controlplane.grpc.DeregisterResponse> getDeregisterNodeMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DeregisterNode",
      requestType = com.nextgen.controlplane.grpc.DeregisterRequest.class,
      responseType = com.nextgen.controlplane.grpc.DeregisterResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.DeregisterRequest,
      com.nextgen.controlplane.grpc.DeregisterResponse> getDeregisterNodeMethod() {
    io.grpc.MethodDescriptor<com.nextgen.controlplane.grpc.DeregisterRequest, com.nextgen.controlplane.grpc.DeregisterResponse> getDeregisterNodeMethod;
    if ((getDeregisterNodeMethod = NodeAgentServiceGrpc.getDeregisterNodeMethod) == null) {
      synchronized (NodeAgentServiceGrpc.class) {
        if ((getDeregisterNodeMethod = NodeAgentServiceGrpc.getDeregisterNodeMethod) == null) {
          NodeAgentServiceGrpc.getDeregisterNodeMethod = getDeregisterNodeMethod =
              io.grpc.MethodDescriptor.<com.nextgen.controlplane.grpc.DeregisterRequest, com.nextgen.controlplane.grpc.DeregisterResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DeregisterNode"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.nextgen.controlplane.grpc.DeregisterRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  com.nextgen.controlplane.grpc.DeregisterResponse.getDefaultInstance()))
              .setSchemaDescriptor(new NodeAgentServiceMethodDescriptorSupplier("DeregisterNode"))
              .build();
        }
      }
    }
    return getDeregisterNodeMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static NodeAgentServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceStub>() {
        @java.lang.Override
        public NodeAgentServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeAgentServiceStub(channel, callOptions);
        }
      };
    return NodeAgentServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports all types of calls on the service
   */
  public static NodeAgentServiceBlockingV2Stub newBlockingV2Stub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceBlockingV2Stub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceBlockingV2Stub>() {
        @java.lang.Override
        public NodeAgentServiceBlockingV2Stub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeAgentServiceBlockingV2Stub(channel, callOptions);
        }
      };
    return NodeAgentServiceBlockingV2Stub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static NodeAgentServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceBlockingStub>() {
        @java.lang.Override
        public NodeAgentServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeAgentServiceBlockingStub(channel, callOptions);
        }
      };
    return NodeAgentServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static NodeAgentServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<NodeAgentServiceFutureStub>() {
        @java.lang.Override
        public NodeAgentServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new NodeAgentServiceFutureStub(channel, callOptions);
        }
      };
    return NodeAgentServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void registerNode(com.nextgen.controlplane.grpc.RegisterRequest request,
        io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.RegisterResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterNodeMethod(), responseObserver);
    }

    /**
     */
    default void sendHeartbeat(com.nextgen.controlplane.grpc.HeartbeatRequest request,
        io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.HeartbeatResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendHeartbeatMethod(), responseObserver);
    }

    /**
     */
    default void deregisterNode(com.nextgen.controlplane.grpc.DeregisterRequest request,
        io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.DeregisterResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDeregisterNodeMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service NodeAgentService.
   */
  public static abstract class NodeAgentServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return NodeAgentServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service NodeAgentService.
   */
  public static final class NodeAgentServiceStub
      extends io.grpc.stub.AbstractAsyncStub<NodeAgentServiceStub> {
    private NodeAgentServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeAgentServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeAgentServiceStub(channel, callOptions);
    }

    /**
     */
    public void registerNode(com.nextgen.controlplane.grpc.RegisterRequest request,
        io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.RegisterResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterNodeMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sendHeartbeat(com.nextgen.controlplane.grpc.HeartbeatRequest request,
        io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.HeartbeatResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendHeartbeatMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void deregisterNode(com.nextgen.controlplane.grpc.DeregisterRequest request,
        io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.DeregisterResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getDeregisterNodeMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service NodeAgentService.
   */
  public static final class NodeAgentServiceBlockingV2Stub
      extends io.grpc.stub.AbstractBlockingStub<NodeAgentServiceBlockingV2Stub> {
    private NodeAgentServiceBlockingV2Stub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeAgentServiceBlockingV2Stub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeAgentServiceBlockingV2Stub(channel, callOptions);
    }

    /**
     */
    public com.nextgen.controlplane.grpc.RegisterResponse registerNode(com.nextgen.controlplane.grpc.RegisterRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getRegisterNodeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.nextgen.controlplane.grpc.HeartbeatResponse sendHeartbeat(com.nextgen.controlplane.grpc.HeartbeatRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getSendHeartbeatMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.nextgen.controlplane.grpc.DeregisterResponse deregisterNode(com.nextgen.controlplane.grpc.DeregisterRequest request) throws io.grpc.StatusException {
      return io.grpc.stub.ClientCalls.blockingV2UnaryCall(
          getChannel(), getDeregisterNodeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do limited synchronous rpc calls to service NodeAgentService.
   */
  public static final class NodeAgentServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<NodeAgentServiceBlockingStub> {
    private NodeAgentServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeAgentServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeAgentServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public com.nextgen.controlplane.grpc.RegisterResponse registerNode(com.nextgen.controlplane.grpc.RegisterRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterNodeMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.nextgen.controlplane.grpc.HeartbeatResponse sendHeartbeat(com.nextgen.controlplane.grpc.HeartbeatRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendHeartbeatMethod(), getCallOptions(), request);
    }

    /**
     */
    public com.nextgen.controlplane.grpc.DeregisterResponse deregisterNode(com.nextgen.controlplane.grpc.DeregisterRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getDeregisterNodeMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service NodeAgentService.
   */
  public static final class NodeAgentServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<NodeAgentServiceFutureStub> {
    private NodeAgentServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected NodeAgentServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new NodeAgentServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.nextgen.controlplane.grpc.RegisterResponse> registerNode(
        com.nextgen.controlplane.grpc.RegisterRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterNodeMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.nextgen.controlplane.grpc.HeartbeatResponse> sendHeartbeat(
        com.nextgen.controlplane.grpc.HeartbeatRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendHeartbeatMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<com.nextgen.controlplane.grpc.DeregisterResponse> deregisterNode(
        com.nextgen.controlplane.grpc.DeregisterRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getDeregisterNodeMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REGISTER_NODE = 0;
  private static final int METHODID_SEND_HEARTBEAT = 1;
  private static final int METHODID_DEREGISTER_NODE = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_REGISTER_NODE:
          serviceImpl.registerNode((com.nextgen.controlplane.grpc.RegisterRequest) request,
              (io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.RegisterResponse>) responseObserver);
          break;
        case METHODID_SEND_HEARTBEAT:
          serviceImpl.sendHeartbeat((com.nextgen.controlplane.grpc.HeartbeatRequest) request,
              (io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.HeartbeatResponse>) responseObserver);
          break;
        case METHODID_DEREGISTER_NODE:
          serviceImpl.deregisterNode((com.nextgen.controlplane.grpc.DeregisterRequest) request,
              (io.grpc.stub.StreamObserver<com.nextgen.controlplane.grpc.DeregisterResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getRegisterNodeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.nextgen.controlplane.grpc.RegisterRequest,
              com.nextgen.controlplane.grpc.RegisterResponse>(
                service, METHODID_REGISTER_NODE)))
        .addMethod(
          getSendHeartbeatMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.nextgen.controlplane.grpc.HeartbeatRequest,
              com.nextgen.controlplane.grpc.HeartbeatResponse>(
                service, METHODID_SEND_HEARTBEAT)))
        .addMethod(
          getDeregisterNodeMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              com.nextgen.controlplane.grpc.DeregisterRequest,
              com.nextgen.controlplane.grpc.DeregisterResponse>(
                service, METHODID_DEREGISTER_NODE)))
        .build();
  }

  private static abstract class NodeAgentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    NodeAgentServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return com.nextgen.controlplane.grpc.NodeAgentProto.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("NodeAgentService");
    }
  }

  private static final class NodeAgentServiceFileDescriptorSupplier
      extends NodeAgentServiceBaseDescriptorSupplier {
    NodeAgentServiceFileDescriptorSupplier() {}
  }

  private static final class NodeAgentServiceMethodDescriptorSupplier
      extends NodeAgentServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    NodeAgentServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (NodeAgentServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new NodeAgentServiceFileDescriptorSupplier())
              .addMethod(getRegisterNodeMethod())
              .addMethod(getSendHeartbeatMethod())
              .addMethod(getDeregisterNodeMethod())
              .build();
        }
      }
    }
    return result;
  }
}
