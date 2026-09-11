package com.nextgen.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import com.nextgen.agent.AgentCredentials;
import com.nextgen.agent.ControlPlaneConnection;
import com.nextgen.agent.NodeAgent;
import com.nextgen.cli.ComposeFileParser.ParsedService;
import com.nextgen.controlplane.ControlPlaneEndpoints;
import com.nextgen.proto.ControlPlaneProto.BuildContextChunk;
import com.nextgen.proto.ControlPlaneProto.DockerContainerInfo;
import com.nextgen.proto.ControlPlaneProto.DockerControlAction;
import com.nextgen.proto.ControlPlaneProto.DockerControlCommand;
import com.nextgen.proto.ControlPlaneProto.DockerControlResult;
import com.nextgen.proto.ControlPlaneProto.DockerImageInfo;
import com.nextgen.proto.ControlPlaneProto.DockerNetworkInfo;
import com.nextgen.proto.ControlPlaneProto.DockerResourceKind;
import com.nextgen.proto.ControlPlaneProto.DockerResourcesSnapshot;
import com.nextgen.proto.ControlPlaneProto.DockerVolumeInfo;
import com.nextgen.proto.ControlPlaneProto.NodeDockerState;
import com.nextgen.proto.ControlPlaneProto.Empty;
import com.nextgen.proto.ControlPlaneProto.JobEventsRequest;
import com.nextgen.proto.ControlPlaneProto.JobList;
import com.nextgen.proto.ControlPlaneProto.JobRequest;
import com.nextgen.proto.ControlPlaneProto.JobStatusRequest;
import com.nextgen.proto.ControlPlaneProto.JobStatusResponse;
import com.nextgen.proto.ControlPlaneProto.JobSubmitResponse;
import com.nextgen.proto.ControlPlaneProto.NodeCapabilities;
import com.nextgen.proto.ControlPlaneProto.NodeInfo;
import com.nextgen.proto.ControlPlaneProto.NodeList;
import com.nextgen.proto.ControlPlaneProto.SetSecretRequest;
import com.nextgen.proto.ControlPlaneProto.TaskCancelRequest;
import com.nextgen.proto.ControlPlaneProto.TaskEvent;
import com.nextgen.proto.ControlPlaneProto.TaskKind;
import com.nextgen.proto.ControlPlaneProto.UploadBuildContextResponse;
import com.nextgen.proto.ControlPlaneProto.ComposeCommand;
import com.nextgen.proto.ControlPlaneProto.ComposeEvent;
import com.nextgen.proto.ControlPlaneProto.ComposeUpRequest;
import com.nextgen.proto.ControlPlaneServiceGrpc;
import com.nextgen.proto.LocalDockerExecutionGrpc;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The {@code nx} command-line tool — Stage R. Modeled directly on the user's own repeated comparison
 * to {@code docker compose build}/{@code up}/{@code down}/{@code ps}/{@code logs}: this is a thin,
 * genuinely-real client over the SAME RPCs the rest of this project already exposes, not a second
 * implementation of any scheduling/execution logic.
 *
 * <p>Reuses, unmodified: {@link ControlPlaneEndpoints}/{@link ControlPlaneConnection} for candidate
 * rotation, {@link AgentCredentials} + {@link NodeAgent#ensureEnrolled} for the exact same enrolment
 * flow a real node uses (see that method's own Javadoc: "Public so ... a second, divergent copy of it"
 * — the CLI is a third consumer of the identical method).
 */
public final class Cli {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int UPLOAD_CHUNK_SIZE = 256 * 1024;

    public static void main(String[] args) {
        try {
            run(args);
        } catch (UsageException e) {
            System.err.println("nx: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("nx: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }
        String command = args[0];
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        switch (command) {
            case "enrol", "enroll" -> cmdEnrol(rest);
            case "up" -> cmdUp(rest);
            case "update" -> cmdUpdate(rest);
            case "rollback" -> cmdRollback(rest);
            case "down" -> cmdDown(rest);
            case "ps" -> cmdPs(rest);
            case "logs" -> cmdLogs(rest);
            case "nodes" -> cmdNodes(rest);
            case "container" -> cmdContainer(rest);
            case "images" -> cmdImages(rest);
            case "volumes" -> cmdVolumes(rest);
            case "networks" -> cmdNetworks(rest);
            case "secret" -> cmdSecret(rest);
            case "cloud" -> cmdCloud(rest);
            case "help", "-h", "--help" -> printUsage();
            default -> throw new UsageException("unknown command '" + command + "' — try 'nx help'");
        }
    }

    private static void printUsage() {
        System.out.println("""
                nx — distributed docker-compose-style execution over the NextGen control plane

                Usage:
                  nx enrol --token <token> --control-plane <host:port> [--ca-cert <path>]
                  nx up <compose-file> [--project NAME] [--build] [--no-follow] [--control-plane <host:port>]
                  nx update <job-id> <compose-file> [--project NAME] [--build] [--no-follow] [--control-plane <host:port>]
                  nx rollback <job-id> [--control-plane <host:port>]
                  nx down <job-id> [--control-plane <host:port>]
                  nx ps [job-id] [--control-plane <host:port>]
                  nx logs <job-id> [--control-plane <host:port>]
                  nx nodes [--control-plane <host:port>]
                  nx container ls [--control-plane <host:port>]
                  nx container <start|stop|restart|rm> <node-id> <container-id> [--control-plane <host:port>]
                  nx images [--control-plane <host:port>]
                  nx images <pull|rm> <node-id> <image-ref> [--control-plane <host:port>]
                  nx images tag <node-id> <source-ref> <target-ref> [--control-plane <host:port>]
                  nx volumes [--control-plane <host:port>]
                  nx volumes <create|rm> <node-id> <name> [--control-plane <host:port>]
                  nx networks [--control-plane <host:port>]
                  nx networks <create|rm> <node-id> <name> [--control-plane <host:port>]
                  nx secret set <name> <value-or-@file> [--control-plane <host:port>]
                  nx cloud up --target <host:port> [--build] <compose-file>
                """);
    }

    // ── Shared connection/credential plumbing ─────────────────────────────

    private static Path cliHomeDir() {
        return Path.of(System.getProperty("user.home", "."), ".nextgen", "cli");
    }

    private static Path endpointFile() {
        return cliHomeDir().resolve("endpoint.txt");
    }

    private static AgentCredentials loadCredentials() {
        AgentCredentials credentials = new AgentCredentials(cliHomeDir());
        credentials.load();
        if (!credentials.hasCertificate()) {
            throw new IllegalStateException(
                    "Not enrolled yet — run 'nx enrol --token <token> --control-plane <host:port>' first");
        }
        return credentials;
    }

    private static String[] resolveHostPort(String explicit) throws IOException {
        String raw = explicit;
        if (raw == null && Files.exists(endpointFile())) {
            raw = Files.readString(endpointFile()).strip();
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "No control plane address known — pass --control-plane <host:port> or run 'nx enrol' first");
        }
        int colon = raw.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Expected host:port, got '" + raw + "'");
        }
        return new String[]{raw.substring(0, colon), raw.substring(colon + 1)};
    }

    /** @param args {@code --tls} opts into mutual TLS (requires a prior {@code nx enrol}); the default
     * is plaintext, matching this project's own {@code TLS_ENABLED=false} default for a simple local
     * deployment (see {@code docker-compose.yml}) — {@code MtlsPolicyInterceptor} runs in permissive
     * audit mode server-side in that case, so no client certificate is needed at all. */
    private static ControlPlaneConnection connect(ArgReader args) throws IOException {
        String[] hostPort = resolveHostPort(args.option("--control-plane"));
        boolean tls = args.flag("--tls");
        AgentCredentials credentials = tls ? loadCredentials() : new AgentCredentials(cliHomeDir());
        ControlPlaneEndpoints endpoints = ControlPlaneEndpoints.single(hostPort[0], Integer.parseInt(hostPort[1]));
        return new ControlPlaneConnection(endpoints, tls, credentials);
    }

    /** Stage W: every unary RPC call site below used a bare {@code blockingStub(connection)} with no
     * bound at all — against a silently-unreachable (not actively-refused) host, that hangs the whole
     * command forever with no error, no hint, nothing to Ctrl-C into. {@code nx enrol} was the one
     * exception (its own 15s deadline via {@code NodeAgent.ensureEnrolled}); every other command now
     * gets the same real bound through this one helper. */
    private static final long DEFAULT_RPC_TIMEOUT_SECONDS = 10;

    private static ControlPlaneServiceGrpc.ControlPlaneServiceBlockingStub blockingStub(
            ControlPlaneConnection connection) {
        return blockingStub(connection).withDeadlineAfter(DEFAULT_RPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    // ── nx enrol ───────────────────────────────────────────────────────

    private static void cmdEnrol(String[] rawArgs) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        String token = args.require("--token");
        String controlPlane = args.require("--control-plane");
        String caCertPath = args.option("--ca-cert");

        int colon = controlPlane.lastIndexOf(':');
        if (colon < 0) {
            throw new UsageException("--control-plane must be host:port, got '" + controlPlane + "'");
        }
        String host = controlPlane.substring(0, colon);
        int port = Integer.parseInt(controlPlane.substring(colon + 1));

        System.setProperty("NEXTGEN_ENROLLMENT_TOKEN", token);
        if (caCertPath != null) {
            System.setProperty("NEXTGEN_CA_CERT", caCertPath);
        }

        Files.createDirectories(cliHomeDir());
        AgentCredentials credentials = new AgentCredentials(cliHomeDir());
        String nodeId = "operator-cli@" + safeHostname();

        NodeAgent.ensureEnrolled(credentials, nodeId, host, port,
                NodeCapabilities.getDefaultInstance(), "nx-cli/1.0");

        Files.writeString(endpointFile(), host + ":" + port);
        System.out.println("Enrolled as '" + nodeId + "' against " + host + ":" + port);
    }

    private static String safeHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown-host";
        }
    }

    // ── nx up ──────────────────────────────────────────────────────────

    private static void cmdUp(String[] rawArgs) throws Exception {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.isEmpty()) {
            throw new UsageException("nx up <compose-file> [--project NAME] [--build] [--no-follow]");
        }
        Path composeFile = Path.of(positionals.get(0));
        String project = args.option("--project");
        if (project == null) {
            project = composeFile.toAbsolutePath().getParent() != null
                    ? composeFile.toAbsolutePath().getParent().getFileName().toString() : "project";
        }
        boolean build = args.flag("--build");
        boolean follow = !args.flag("--no-follow");

        List<ParsedService> services = ComposeFileParser.parse(composeFile);
        ControlPlaneConnection connection = connect(args);

        Map<String, String> contextIds = new HashMap<>();
        Map<String, String> sha256s = new HashMap<>();
        if (build) {
            for (ParsedService service : services) {
                if (service.needsBuild()) {
                    System.out.println("Uploading build context for '" + service.name() + "'...");
                    UploadBuildContextResponse response =
                            uploadBuildContext(connection, Path.of(service.buildContext()));
                    contextIds.put(service.name(), response.getContextId());
                    sha256s.put(service.name(), response.getSha256());
                }
            }
        }

        String payloadJson = ComposeFileParser.buildJobPayload(project, services, contextIds, sha256s);
        String jobId = project + "-" + UUID.randomUUID().toString().substring(0, 8);

        JobSubmitResponse response = blockingStub(connection).submitJob(JobRequest.newBuilder()
                .setJobId(jobId)
                .setKind(TaskKind.TASK_KIND_DOCKER_COMPOSE_SERVICE)
                .setPayloadJson(payloadJson)
                .setSubTaskCount(services.size())
                .build());

        System.out.println("Job '" + jobId + "': " + response.getResult());
        if (!response.getResult().startsWith("ACCEPTED")) {
            return;
        }

        if (follow) {
            followJobEvents(connection, jobId, resolveTaskIdToServiceName(connection, jobId, services), true);
        } else {
            System.out.println("Started detached — use 'nx logs " + jobId + "' or 'nx ps " + jobId + "' to check on it.");
        }
    }

    // ── nx update / nx rollback ────────────────────────────────────────
    // Stage PP. Identified by job-id (not "project name" — job-id is this system's real lookup key,
    // matching every other job-scoped command here), following the same nx up-style parse/build/submit
    // path, but marking the submission as a rolling update via supersedes_job_id.

    private static void cmdUpdate(String[] rawArgs) throws Exception {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.size() < 2) {
            throw new UsageException("nx update <job-id> <compose-file> [--build] [--no-follow]");
        }
        String oldJobId = positionals.get(0);
        Path composeFile = Path.of(positionals.get(1));
        boolean build = args.flag("--build");
        boolean follow = !args.flag("--no-follow");

        List<ParsedService> services = ComposeFileParser.parse(composeFile);
        ControlPlaneConnection connection = connect(args);

        String project = args.option("--project");
        if (project == null) {
            project = composeFile.toAbsolutePath().getParent() != null
                    ? composeFile.toAbsolutePath().getParent().getFileName().toString() : "project";
        }

        Map<String, String> contextIds = new HashMap<>();
        Map<String, String> sha256s = new HashMap<>();
        if (build) {
            for (ParsedService service : services) {
                if (service.needsBuild()) {
                    System.out.println("Uploading build context for '" + service.name() + "'...");
                    UploadBuildContextResponse response =
                            uploadBuildContext(connection, Path.of(service.buildContext()));
                    contextIds.put(service.name(), response.getContextId());
                    sha256s.put(service.name(), response.getSha256());
                }
            }
        }

        String payloadJson = ComposeFileParser.buildJobPayload(project, services, contextIds, sha256s);
        String newJobId = oldJobId + "-update-" + UUID.randomUUID().toString().substring(0, 8);

        System.out.println("Rolling update: replacing '" + oldJobId + "' with '" + newJobId
                + "', one replica at a time...");
        JobSubmitResponse response = blockingStub(connection).submitJob(JobRequest.newBuilder()
                .setJobId(newJobId)
                .setKind(TaskKind.TASK_KIND_DOCKER_COMPOSE_SERVICE)
                .setPayloadJson(payloadJson)
                .setSubTaskCount(services.size())
                .setSupersedesJobId(oldJobId)
                .build());

        System.out.println("Job '" + newJobId + "': " + response.getResult());
        if (!response.getResult().startsWith("ACCEPTED")) {
            return;
        }
        if (follow) {
            followJobEvents(connection, newJobId, resolveTaskIdToServiceName(connection, newJobId, services), true);
        } else {
            System.out.println("Use 'nx logs " + newJobId + "' or 'nx ps " + newJobId + "' to check on it.");
        }
    }

    private static void cmdRollback(String[] rawArgs) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.isEmpty()) {
            throw new UsageException("nx rollback <job-id>");
        }
        ControlPlaneConnection connection = connect(args);
        JobSubmitResponse response = blockingStub(connection)
                .rollbackJob(JobStatusRequest.newBuilder().setJobId(positionals.get(0)).build());
        System.out.println(response.getResult());
    }

    private static Map<String, String> resolveTaskIdToServiceName(ControlPlaneConnection connection, String jobId,
                                                                   List<ParsedService> services) {
        Map<String, String> names = new HashMap<>();
        JobStatusResponse status = blockingStub(connection)
                .getJobStatus(JobStatusRequest.newBuilder().setJobId(jobId).build());
        List<String> taskIds = status.getTaskIdsList();
        for (int i = 0; i < taskIds.size() && i < services.size(); i++) {
            names.put(taskIds.get(i), services.get(i).name());
        }
        return names;
    }

    /** How long {@link #followJobEvents} waits for the FIRST signal (a real event, or an honest error)
     * before giving up on a silently-unreachable (not actively-refused) control plane — matches
     * {@code NodeAgent.ensureEnrolled}'s own 15s convention. Deliberately NOT a cap on the whole follow
     * session: once the stream is confirmed alive, following continues indefinitely (Ctrl+C to stop),
     * matching `docker compose up`'s own attached behavior — a legitimately long-running job must never
     * be cut off by a flat overall timeout. */
    private static final long FOLLOW_CONNECT_TIMEOUT_SECONDS = 15;

    private static void followJobEvents(ControlPlaneConnection connection, String jobId,
                                        Map<String, String> taskIdToServiceName, boolean follow)
            throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        CountDownLatch connected = new CountDownLatch(1);
        connection.asyncStub().streamJobEvents(
                JobEventsRequest.newBuilder().setJobId(jobId).setFollow(follow).build(),
                new StreamObserver<>() {
                    @Override
                    public void onNext(TaskEvent event) {
                        connected.countDown();
                        String service = taskIdToServiceName.getOrDefault(event.getTaskId(), event.getTaskId());
                        if (event.hasLog()) {
                            System.out.println(service + " | " + event.getLog().getLine());
                        } else if (event.hasProgress()) {
                            System.out.println(service + " | [" + event.getProgress().getState() + "]");
                        } else if (event.hasResult()) {
                            System.out.println(service + " | "
                                    + (event.getResult().getSuccess() ? "COMPLETED" : "FAILED: "
                                    + event.getResult().getError()));
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        connected.countDown();
                        System.err.println("stream error: " + t.getMessage());
                        done.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        connected.countDown();
                        done.countDown();
                    }
                });
        if (!connected.await(FOLLOW_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            System.err.println("No response from the control plane within " + FOLLOW_CONNECT_TIMEOUT_SECONDS
                    + "s — is it reachable?");
            return;
        }
        done.await(); // Ctrl+C to stop following, matching `docker compose up`'s own attached behavior.
    }

    private static UploadBuildContextResponse uploadBuildContext(ControlPlaneConnection connection, Path contextDir)
            throws Exception {
        Path tarball = Files.createTempFile("nx-context-", ".tar.gz");
        try {
            Process tar = new ProcessBuilder("tar", "-czf", tarball.toString(), "-C", contextDir.toString(), ".")
                    .redirectErrorStream(true).start();
            if (!tar.waitFor(120, TimeUnit.SECONDS) || tar.exitValue() != 0) {
                throw new IOException("Could not tar build context at " + contextDir);
            }
            String localSha256 = sha256Hex(tarball);

            CountDownLatch done = new CountDownLatch(1);
            UploadBuildContextResponse[] result = new UploadBuildContextResponse[1];
            Throwable[] error = new Throwable[1];
            StreamObserver<BuildContextChunk> outbound = connection.asyncStub()
                    .uploadBuildContext(new StreamObserver<>() {
                        @Override public void onNext(UploadBuildContextResponse value) { result[0] = value; }
                        @Override public void onError(Throwable t) { error[0] = t; done.countDown(); }
                        @Override public void onCompleted() { done.countDown(); }
                    });
            try (InputStream in = Files.newInputStream(tarball)) {
                byte[] buffer = new byte[UPLOAD_CHUNK_SIZE];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    outbound.onNext(BuildContextChunk.newBuilder()
                            .setData(ByteString.copyFrom(buffer, 0, read)).build());
                }
            }
            outbound.onCompleted();
            done.await(120, TimeUnit.SECONDS);
            if (error[0] != null) {
                throw new IOException("Build context upload failed: " + error[0].getMessage());
            }
            if (!result[0].getSha256().equalsIgnoreCase(localSha256)) {
                throw new IOException("Build context upload corrupted in transit (hash mismatch)");
            }
            return result[0];
        } finally {
            Files.deleteIfExists(tarball);
        }
    }

    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    // ── nx down ────────────────────────────────────────────────────────

    private static void cmdDown(String[] rawArgs) {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.isEmpty()) {
            throw new UsageException("nx down <job-id>");
        }
        String jobId = positionals.get(0);
        ControlPlaneConnection connection;
        try {
            connection = connect(args);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        JobStatusResponse status = getJobStatusOrUsageError(connection, jobId);
        for (String taskId : status.getTaskIdsList()) {
            var response = blockingStub(connection).cancelTask(TaskCancelRequest.newBuilder()
                    .setTaskId(taskId).setReason("nx down").build());
            System.out.println(taskId + ": " + (response.getAccepted() ? "cancel requested" : response.getMessage()));
        }
    }

    // ── nx ps ──────────────────────────────────────────────────────────

    private static void cmdPs(String[] rawArgs) {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        ControlPlaneConnection connection;
        try {
            connection = connect(args);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (!positionals.isEmpty()) {
            printJobStatus(getJobStatusOrUsageError(connection, positionals.get(0)));
        } else {
            JobList list = blockingStub(connection).listJobs(Empty.getDefaultInstance());
            list.getJobsList().forEach(Cli::printJobStatus);
        }
    }

    /** Stage EE: {@code nx down}/{@code nx ps <job-id>} against an unknown job previously fell through
     * to {@code main()}'s generic catch-all (exit code 1) — indistinguishable from a real internal or
     * transport failure. {@code NOT_FOUND} is already a clean, honest server response (see {@code
     * ControlPlaneServiceImpl.getJobStatus}); surfacing it as a {@link UsageException} gives it the
     * dedicated exit code 2 that distinguishes "you asked for something that doesn't exist" from
     * "something actually went wrong." */
    private static JobStatusResponse getJobStatusOrUsageError(ControlPlaneConnection connection, String jobId) {
        try {
            return blockingStub(connection).getJobStatus(JobStatusRequest.newBuilder().setJobId(jobId).build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                String description = e.getStatus().getDescription();
                throw new UsageException(description != null ? description : "no such job: " + jobId);
            }
            throw e;
        }
    }

    private static void printJobStatus(JobStatusResponse job) {
        System.out.printf("%-24s %-16s %d/%d completed, %d failed%n",
                job.getJobId(), job.getState(), job.getCompletedCount(), job.getSubTaskCount(), job.getFailedCount());
        try {
            JsonNode result = MAPPER.readTree(job.getCombinedResultJson().isBlank() ? "{}" : job.getCombinedResultJson());
            if (result.has("services")) {
                result.get("services").forEach(s -> System.out.printf("  %-16s %-12s node=%s container=%s%n",
                        s.path("name").asText(), s.path("state").asText(),
                        s.path("node_id").asText(), s.path("container_id").asText()));
            }
        } catch (Exception ignored) {
            // Not-yet-reduced or malformed result JSON — the summary line above is still informative.
        }
    }

    // ── nx logs ────────────────────────────────────────────────────────

    private static void cmdLogs(String[] rawArgs) throws InterruptedException {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.isEmpty()) {
            throw new UsageException("nx logs <job-id> [--follow]");
        }
        ControlPlaneConnection connection;
        try {
            connection = connect(args);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        // Stage FF: the server now keeps a bounded per-job history buffer (JobEventBroadcaster), so
        // --follow can mean what it means for `docker logs`/`kubectl logs` — replay history and exit
        // by default, only keep tailing live output when explicitly requested.
        boolean follow = args.flag("--follow");
        followJobEvents(connection, positionals.get(0), Map.of(), follow);
    }

    // ── nx nodes ───────────────────────────────────────────────────────

    private static void cmdNodes(String[] rawArgs) {
        ArgReader args = new ArgReader(rawArgs);
        ControlPlaneConnection connection;
        try {
            connection = connect(args);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        NodeList nodes = blockingStub(connection).getNodes(Empty.getDefaultInstance());
        System.out.printf("%-20s %-10s %-8s %-16s%n", "NODE", "STATUS", "DOCKER", "DOCKER_VERSION");
        for (NodeInfo node : nodes.getNodesList()) {
            System.out.printf("%-20s %-10s %-8s %-16s%n", node.getNodeId(), node.getStatus(),
                    node.getCapabilities().getDockerAvailable() ? "yes" : "no",
                    node.getCapabilities().getDockerVersion());
        }
    }

    // ── nx container ───────────────────────────────────────────────────
    // Real per-container listing + control, cluster-wide — distinct from `nx ps`/`nx down`, which stay
    // job-scoped. Backed by GetDockerResources/ControlDockerContainer (Stage T): every row/action here
    // is a real docker ps/start/stop/restart/rm result reported by the node that actually ran it, never
    // a job/task abstraction.

    private static void cmdContainer(String[] rawArgs) throws IOException {
        if (rawArgs.length == 0) {
            throw new UsageException("nx container <ls|start|stop|restart|rm> ...");
        }
        String sub = rawArgs[0];
        String[] rest = java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length);
        switch (sub) {
            case "ls" -> cmdContainerLs(rest);
            case "start" -> cmdContainerControl(rest, DockerControlAction.DOCKER_CONTROL_ACTION_START);
            case "stop" -> cmdContainerControl(rest, DockerControlAction.DOCKER_CONTROL_ACTION_STOP);
            case "restart" -> cmdContainerControl(rest, DockerControlAction.DOCKER_CONTROL_ACTION_RESTART);
            case "rm" -> cmdContainerControl(rest, DockerControlAction.DOCKER_CONTROL_ACTION_REMOVE);
            default -> throw new UsageException("unknown 'nx container' subcommand '" + sub + "'");
        }
    }

    private static void cmdContainerLs(String[] rawArgs) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        ControlPlaneConnection connection = connect(args);
        DockerResourcesSnapshot snapshot =
                blockingStub(connection).getDockerResources(Empty.getDefaultInstance());
        System.out.printf("%-16s %-14s %-22s %-24s %-10s %-20s%n",
                "NODE", "CONTAINER_ID", "NAME", "IMAGE", "STATUS", "PORTS");
        for (NodeDockerState nodeState : snapshot.getNodesList()) {
            for (DockerContainerInfo c : nodeState.getReport().getContainersList()) {
                System.out.printf("%-16s %-14s %-22s %-24s %-10s %-20s%n",
                        nodeState.getNodeId(), shortId(c.getContainerId()), c.getName(), c.getImage(),
                        c.getStatus(), String.join(",", c.getPortsList()));
            }
        }
    }

    private static void cmdContainerControl(String[] rawArgs, DockerControlAction action) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.size() < 2) {
            throw new UsageException("nx container <start|stop|restart|rm> <node-id> <container-id>");
        }
        ControlPlaneConnection connection = connect(args);
        DockerControlResult result = blockingStub(connection).controlDockerContainer(DockerControlCommand.newBuilder()
                .setNodeId(positionals.get(0))
                .setContainerId(positionals.get(1))
                .setAction(action)
                .build());
        System.out.println((result.getOk() ? "OK: " : "FAILED: ") + result.getMessage());
    }

    private static String shortId(String id) {
        return id.length() > 12 ? id.substring(0, 12) : id;
    }

    // ── nx secret ──────────────────────────────────────
    // Stage NN. Only "set" today — reading a secret's value back is deliberately not exposed (the whole
    // point is that only the control plane and the node a task is dispatched to ever see the plaintext).

    private static void cmdSecret(String[] rawArgs) throws IOException {
        if (rawArgs.length == 0 || !"set".equals(rawArgs[0])) {
            throw new UsageException("nx secret set <name> <value-or-@file>");
        }
        ArgReader args = new ArgReader(java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length));
        List<String> positionals = args.positionals();
        if (positionals.size() < 2) {
            throw new UsageException("nx secret set <name> <value-or-@file>");
        }
        String name = positionals.get(0);
        String rawValue = positionals.get(1);
        byte[] value = rawValue.startsWith("@")
                ? Files.readAllBytes(Path.of(rawValue.substring(1)))
                : rawValue.getBytes(StandardCharsets.UTF_8);

        ControlPlaneConnection connection = connect(args);
        blockingStub(connection).setSecret(SetSecretRequest.newBuilder()
                .setName(name)
                .setValue(ByteString.copyFrom(value))
                .build());
        System.out.println("OK: secret '" + name + "' stored (" + value.length + " bytes)");
    }

    // ── nx images / nx volumes / nx networks ──────────────────────────
    // Stage FF: pull/rm/tag for images, create/rm for volumes/networks — extending Stage T's
    // originally list-only coverage of these three resource types (containers already had full
    // start/stop/restart/rm). Each write action targets exactly one node (docker images/volumes/
    // networks are node-local, not cluster-wide resources), so every subcommand below takes a
    // <node-id> the same way `nx container start/stop/...` already does.

    private static void cmdImages(String[] rawArgs) throws IOException {
        if (rawArgs.length > 0 && !rawArgs[0].startsWith("--")) {
            String[] rest = java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length);
            switch (rawArgs[0]) {
                case "pull" -> cmdDockerResourceControl(rest, DockerResourceKind.DOCKER_RESOURCE_KIND_IMAGE,
                        DockerControlAction.DOCKER_CONTROL_ACTION_PULL, "nx images pull <node-id> <image-ref>");
                case "rm" -> cmdDockerResourceControl(rest, DockerResourceKind.DOCKER_RESOURCE_KIND_IMAGE,
                        DockerControlAction.DOCKER_CONTROL_ACTION_REMOVE, "nx images rm <node-id> <image-ref>");
                case "tag" -> cmdImageTag(rest);
                default -> throw new UsageException("unknown 'nx images' subcommand '" + rawArgs[0] + "'");
            }
            return;
        }
        cmdImagesLs(rawArgs);
    }

    private static void cmdImagesLs(String[] rawArgs) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        ControlPlaneConnection connection = connect(args);
        DockerResourcesSnapshot snapshot =
                blockingStub(connection).getDockerResources(Empty.getDefaultInstance());
        System.out.printf("%-16s %-30s %-14s %-14s%n", "NODE", "REPOSITORY:TAG", "IMAGE_ID", "SIZE");
        for (NodeDockerState nodeState : snapshot.getNodesList()) {
            for (DockerImageInfo i : nodeState.getReport().getImagesList()) {
                System.out.printf("%-16s %-30s %-14s %-14s%n", nodeState.getNodeId(),
                        i.getRepository() + ":" + i.getTag(), shortId(i.getImageId()), formatBytes(i.getSizeBytes()));
            }
        }
    }

    private static void cmdImageTag(String[] rawArgs) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.size() < 3) {
            throw new UsageException("nx images tag <node-id> <source-ref> <target-ref>");
        }
        ControlPlaneConnection connection = connect(args);
        DockerControlResult result = blockingStub(connection).controlDockerContainer(DockerControlCommand.newBuilder()
                .setNodeId(positionals.get(0))
                .setResourceKind(DockerResourceKind.DOCKER_RESOURCE_KIND_IMAGE)
                .setAction(DockerControlAction.DOCKER_CONTROL_ACTION_TAG)
                .setResourceId(positionals.get(1))
                .setTagTarget(positionals.get(2))
                .build());
        System.out.println((result.getOk() ? "OK: " : "FAILED: ") + result.getMessage());
    }

    private static void cmdVolumes(String[] rawArgs) throws IOException {
        if (rawArgs.length > 0 && !rawArgs[0].startsWith("--")) {
            String[] rest = java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length);
            switch (rawArgs[0]) {
                case "create" -> cmdDockerResourceControl(rest, DockerResourceKind.DOCKER_RESOURCE_KIND_VOLUME,
                        DockerControlAction.DOCKER_CONTROL_ACTION_CREATE, "nx volumes create <node-id> <name>");
                case "rm" -> cmdDockerResourceControl(rest, DockerResourceKind.DOCKER_RESOURCE_KIND_VOLUME,
                        DockerControlAction.DOCKER_CONTROL_ACTION_REMOVE, "nx volumes rm <node-id> <name>");
                default -> throw new UsageException("unknown 'nx volumes' subcommand '" + rawArgs[0] + "'");
            }
            return;
        }
        cmdVolumesLs(rawArgs);
    }

    private static void cmdVolumesLs(String[] rawArgs) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        ControlPlaneConnection connection = connect(args);
        DockerResourcesSnapshot snapshot =
                blockingStub(connection).getDockerResources(Empty.getDefaultInstance());
        System.out.printf("%-16s %-24s %-12s %-30s%n", "NODE", "NAME", "DRIVER", "MOUNTPOINT");
        for (NodeDockerState nodeState : snapshot.getNodesList()) {
            for (DockerVolumeInfo v : nodeState.getReport().getVolumesList()) {
                System.out.printf("%-16s %-24s %-12s %-30s%n",
                        nodeState.getNodeId(), v.getName(), v.getDriver(), v.getMountpoint());
            }
        }
    }

    private static void cmdNetworks(String[] rawArgs) throws IOException {
        if (rawArgs.length > 0 && !rawArgs[0].startsWith("--")) {
            String[] rest = java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length);
            switch (rawArgs[0]) {
                case "create" -> cmdDockerResourceControl(rest, DockerResourceKind.DOCKER_RESOURCE_KIND_NETWORK,
                        DockerControlAction.DOCKER_CONTROL_ACTION_CREATE, "nx networks create <node-id> <name>");
                case "rm" -> cmdDockerResourceControl(rest, DockerResourceKind.DOCKER_RESOURCE_KIND_NETWORK,
                        DockerControlAction.DOCKER_CONTROL_ACTION_REMOVE, "nx networks rm <node-id> <name>");
                default -> throw new UsageException("unknown 'nx networks' subcommand '" + rawArgs[0] + "'");
            }
            return;
        }
        cmdNetworksLs(rawArgs);
    }

    private static void cmdNetworksLs(String[] rawArgs) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        ControlPlaneConnection connection = connect(args);
        DockerResourcesSnapshot snapshot =
                blockingStub(connection).getDockerResources(Empty.getDefaultInstance());
        System.out.printf("%-16s %-14s %-24s %-12s %-10s%n", "NODE", "NETWORK_ID", "NAME", "DRIVER", "SCOPE");
        for (NodeDockerState nodeState : snapshot.getNodesList()) {
            for (DockerNetworkInfo n : nodeState.getReport().getNetworksList()) {
                System.out.printf("%-16s %-14s %-24s %-12s %-10s%n",
                        nodeState.getNodeId(), shortId(n.getNetworkId()), n.getName(), n.getDriver(), n.getScope());
            }
        }
    }

    /** Shared by {@code nx images pull/rm}, {@code nx volumes create/rm}, {@code nx networks create/rm}
     * — every one of these is {@code <node-id> <resource-id>} plus a fixed kind/action pair, mirroring
     * {@link #cmdContainerControl}'s identical shape for containers. */
    private static void cmdDockerResourceControl(String[] rawArgs, DockerResourceKind kind,
            DockerControlAction action, String usage) throws IOException {
        ArgReader args = new ArgReader(rawArgs);
        List<String> positionals = args.positionals();
        if (positionals.size() < 2) {
            throw new UsageException(usage);
        }
        ControlPlaneConnection connection = connect(args);
        DockerControlResult result = blockingStub(connection).controlDockerContainer(DockerControlCommand.newBuilder()
                .setNodeId(positionals.get(0))
                .setResourceKind(kind)
                .setAction(action)
                .setResourceId(positionals.get(1))
                .build());
        System.out.println((result.getOk() ? "OK: " : "FAILED: ") + result.getMessage());
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) {
            return "n/a";
        }
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double value = bytes;
        int i = 0;
        while (value >= 1024 && i < units.length - 1) {
            value /= 1024;
            i++;
        }
        return String.format("%.1f%s", value, units[i]);
    }

    // ── nx cloud up ────────────────────────────────────────────────────

    private static void cmdCloud(String[] rawArgs) throws Exception {
        List<String> positionals = new ArrayList<>();
        for (String a : rawArgs) {
            if (!a.startsWith("--")) {
                positionals.add(a);
            }
        }
        if (positionals.isEmpty() || !positionals.get(0).equals("up")) {
            throw new UsageException("nx cloud up --target <host:port> [--build] <compose-file>");
        }
        String[] sub = java.util.Arrays.copyOfRange(rawArgs, 1, rawArgs.length);
        ArgReader args = new ArgReader(sub);
        String target = args.require("--target");
        boolean build = args.flag("--build");
        boolean tls = args.flag("--tls");
        List<String> files = args.positionals();
        if (files.isEmpty()) {
            throw new UsageException("nx cloud up --target <host:port> [--build] <compose-file>");
        }
        Path composeFile = Path.of(files.get(0));
        String composeYaml = Files.readString(composeFile);
        String project = composeFile.toAbsolutePath().getParent() != null
                ? composeFile.toAbsolutePath().getParent().getFileName().toString() : "project";

        int colon = target.lastIndexOf(':');
        if (colon < 0) {
            throw new UsageException("--target must be host:port, got '" + target + "'");
        }
        String host = target.substring(0, colon);
        int port = Integer.parseInt(target.substring(colon + 1));

        ManagedChannel channel;
        if (tls) {
            AgentCredentials credentials = loadCredentials();
            channel = NettyChannelBuilder.forAddress(host, port)
                    .sslContext(com.nextgen.security.TlsConfig.mutualClientContext(
                            credentials.caCertificatePem(), credentials.certificate().orElseThrow(),
                            credentials.privateKey()))
                    .build();
        } else {
            // Plaintext, matching this project's own TLS_ENABLED=false default — MtlsPolicyInterceptor
            // runs in permissive audit mode server-side in that case, so no client certificate is needed.
            channel = NettyChannelBuilder.forAddress(host, port).usePlaintext().build();
        }
        try {
            LocalDockerExecutionGrpc.LocalDockerExecutionStub stub = LocalDockerExecutionGrpc.newStub(channel);
            CountDownLatch done = new CountDownLatch(1);
            StreamObserver<ComposeCommand> outbound = stub.runCompose(new StreamObserver<>() {
                @Override
                public void onNext(ComposeEvent event) {
                    if (event.hasLog()) {
                        System.out.println(event.getLog().getLine());
                    } else if (event.hasFinished()) {
                        System.out.println((event.getFinished().getSuccess() ? "OK: " : "FAILED: ")
                                + event.getFinished().getMessage());
                    }
                }
                @Override public void onError(Throwable t) { System.err.println("error: " + t.getMessage()); done.countDown(); }
                @Override public void onCompleted() { done.countDown(); }
            });
            outbound.onNext(ComposeCommand.newBuilder()
                    .setUp(ComposeUpRequest.newBuilder().setProjectName(project)
                            .setComposeYaml(composeYaml).setBuild(build).build())
                    .build());
            done.await(); // Ctrl+C to stop following.
        } finally {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    // ── Tiny arg parser ────────────────────────────────────────────────

    // Package-private (not private) so ArgReaderTest can assert on this specific exception type.
    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(String message) {
            super(message);
        }
    }

    /** No external CLI-parsing dependency for a handful of flags — this covers exactly what the
     * command table above needs: {@code --flag value}, bare {@code --flag} booleans, and positionals. */
    // Package-private (not private) so ArgReaderTest can exercise it directly, real-object-not-mocked,
    // matching this project's established testing discipline.
    static final class ArgReader {
        private final Map<String, String> options = new HashMap<>();
        // Stage EE: names ArgReader itself defaulted to "true" because no following value token was
        // present (either end-of-args, or the next token itself looked like another --flag) — kept
        // separate from `options` so require() can tell a genuine boolean flag apart from a value-taking
        // option whose value was accidentally omitted (e.g. `nx enrol --token --control-plane host:1234`
        // previously silently attempted a doomed enrolment with literal token "true" instead of failing
        // with a clear "missing value for --token").
        private final java.util.Set<String> defaultedFlags = new java.util.HashSet<>();
        private final List<String> positional = new ArrayList<>();

        ArgReader(String[] args) {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--")) {
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        options.put(a, args[++i]);
                    } else {
                        options.put(a, "true");
                        defaultedFlags.add(a);
                    }
                } else {
                    positional.add(a);
                }
            }
        }

        String option(String name) {
            return options.get(name);
        }

        String require(String name) {
            String v = options.get(name);
            if (v == null) {
                throw new UsageException("missing required option " + name);
            }
            if (defaultedFlags.contains(name)) {
                throw new UsageException("missing value for " + name);
            }
            return v;
        }

        boolean flag(String name) {
            return options.containsKey(name);
        }

        List<String> positionals() {
            return positional;
        }
    }
}
