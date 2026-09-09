package org.example.executor.core;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.example.executor.config.ExecutorProperties;
import org.example.ops.contract.ActionRequest;
import org.example.ops.contract.ActionResult;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class DockerActionExecutor {

    private final DockerClient docker;
    private final TargetRegistry targets;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DockerActionExecutor(DockerClient docker, TargetRegistry targets) {
        this.docker = docker;
        this.targets = targets;
    }

    public ActionResult execute(ActionRequest request) {
        ExecutorProperties.Target target = targets.require(request.target(), request.action());
        try {
            return switch (request.action()) {
                case GET_CONTAINER_STATE -> state(request, target);
                case GET_CONTAINER_LOGS -> logs(request, target);
                case GET_CONTAINER_STATS -> stats(request, target);
                case CHECK_HTTP_HEALTH -> health(request, target);
                case RESTART_CONTAINER -> restart(request, target);
                case RECREATE_WITH_STABLE_IMAGE -> recreate(request, target);
                case RESTORE_SNAPSHOT -> restore(request, target);
                case DISCARD_SNAPSHOT -> discardSnapshot(request, target);
            };
        } catch (Exception ex) {
            return ActionResult.failure(request, ex.getMessage());
        }
    }

    private ActionResult state(ActionRequest request, ExecutorProperties.Target target) {
        InspectContainerResponse inspect = docker.inspectContainerCmd(target.getContainerName()).exec();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("containerId", inspect.getId());
        data.put("image", inspect.getConfig().getImage());
        data.put("status", inspect.getState().getStatus());
        data.put("running", inspect.getState().getRunning());
        data.put("startedAt", inspect.getState().getStartedAt());
        data.put("exitCode", inspect.getState().getExitCodeLong());
        return ActionResult.success(request, "Container state collected", data, Map.of());
    }

    private ActionResult logs(ActionRequest request, ExecutorProperties.Target target) throws InterruptedException {
        int tail = Math.min(1000, Math.max(20,
                Integer.parseInt(request.parameters().getOrDefault("tail", "200"))));
        StringBuilder output = new StringBuilder();
        docker.logContainerCmd(target.getContainerName())
                .withStdOut(true)
                .withStdErr(true)
                .withTail(tail)
                .exec(new ResultCallback.Adapter<Frame>() {
                    @Override
                    public void onNext(Frame frame) {
                        output.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                    }
                }).awaitCompletion(15, TimeUnit.SECONDS);
        return ActionResult.success(request, "Container logs collected",
                Map.of("tail", tail, "logs", output.toString()), Map.of());
    }

    private ActionResult stats(ActionRequest request, ExecutorProperties.Target target) throws InterruptedException {
        AtomicReference<Statistics> value = new AtomicReference<>();
        docker.statsCmd(target.getContainerName())
                .withNoStream(true)
                .exec(new ResultCallback.Adapter<Statistics>() {
                    @Override
                    public void onNext(Statistics statistics) {
                        value.set(statistics);
                    }
                }).awaitCompletion(10, TimeUnit.SECONDS);
        Statistics stats = value.get();
        Map<String, Object> data = stats == null ? Map.of() : Map.of(
                "cpu", stats.getCpuStats(),
                "memory", stats.getMemoryStats(),
                "networks", stats.getNetworks() == null ? Map.of() : stats.getNetworks());
        return ActionResult.success(request, "Container statistics collected", data, Map.of());
    }

    private ActionResult health(ActionRequest request, ExecutorProperties.Target target) throws Exception {
        HttpRequest healthRequest = HttpRequest.newBuilder(URI.create(target.getHealthUrl()))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = http.send(healthRequest, HttpResponse.BodyHandlers.ofString());
        return ActionResult.success(request, "Health endpoint checked",
                Map.of("statusCode", response.statusCode(), "body", response.body()), Map.of());
    }

    private ActionResult restart(ActionRequest request, ExecutorProperties.Target target) {
        docker.restartContainerCmd(target.getContainerName()).withTimeout(20).exec();
        return ActionResult.success(request, "Container restarted", Map.of(), Map.of());
    }

    private ActionResult recreate(ActionRequest request, ExecutorProperties.Target target) throws InterruptedException {
        ensureImageAvailable(target.getStableImage());
        InspectContainerResponse current = docker.inspectContainerCmd(target.getContainerName()).exec();
        String suffix = request.requestId().replaceAll("[^a-zA-Z0-9_.-]", "-");
        if (suffix.length() > 48) {
            suffix = suffix.substring(suffix.length() - 48);
        }
        String backupName = target.getContainerName() + "-aiops-backup-" + suffix;
        docker.stopContainerCmd(current.getId()).withTimeout(20).exec();
        boolean renamed = false;
        try {
            docker.renameContainerCmd(current.getId()).withName(backupName).exec();
            renamed = true;
            CreateContainerResponse created = docker.createContainerCmd(target.getStableImage())
                    .withName(target.getContainerName())
                    .withEnv(current.getConfig().getEnv())
                    .withLabels(current.getConfig().getLabels())
                    .withExposedPorts(current.getConfig().getExposedPorts())
                    .withHostConfig(current.getHostConfig())
                    .exec();
            docker.startContainerCmd(created.getId()).exec();
            Map<String, String> snapshot = Map.of(
                    "backupContainerId", current.getId(),
                    "backupContainerName", backupName,
                    "originalImage", current.getConfig().getImage());
            return ActionResult.success(request, "Container recreated with stable image",
                    Map.of("containerId", created.getId(), "image", target.getStableImage()), snapshot);
        } catch (RuntimeException ex) {
            if (renamed) {
                restoreBackupAfterFailedRecreate(target.getContainerName(), current.getId());
            } else {
                docker.startContainerCmd(current.getId()).exec();
            }
            throw ex;
        }
    }

    private void ensureImageAvailable(String image) throws InterruptedException {
        try {
            docker.inspectImageCmd(image).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException ex) {
            docker.pullImageCmd(image).start().awaitCompletion(2, TimeUnit.MINUTES);
        }
    }

    private ActionResult restore(ActionRequest request, ExecutorProperties.Target target) {
        String backupId = request.parameters().get("backupContainerId");
        InspectContainerResponse backup = docker.inspectContainerCmd(backupId).exec();
        String actualName = backup.getName().startsWith("/") ? backup.getName().substring(1) : backup.getName();
        String requiredPrefix = target.getContainerName() + "-aiops-backup-";
        if (!actualName.startsWith(requiredPrefix)) {
            throw new IllegalArgumentException("Snapshot does not belong to the requested target");
        }
        removeCurrentContainer(target.getContainerName());
        docker.renameContainerCmd(backupId).withName(target.getContainerName()).exec();
        docker.startContainerCmd(backupId).exec();
        return ActionResult.success(request, "Rollback snapshot restored",
                Map.of("containerId", backupId, "image", backup.getConfig().getImage()), Map.of());
    }

    private ActionResult discardSnapshot(ActionRequest request, ExecutorProperties.Target target) {
        String backupId = request.parameters().get("backupContainerId");
        InspectContainerResponse backup = docker.inspectContainerCmd(backupId).exec();
        String actualName = backup.getName().startsWith("/") ? backup.getName().substring(1) : backup.getName();
        if (!actualName.startsWith(target.getContainerName() + "-aiops-backup-")) {
            throw new IllegalArgumentException("Snapshot does not belong to the requested target");
        }
        docker.removeContainerCmd(backupId).withForce(false).withRemoveVolumes(false).exec();
        return ActionResult.success(request, "Verified rollback snapshot discarded",
                Map.of("backupContainerId", backupId), Map.of());
    }

    private void restoreBackupAfterFailedRecreate(String containerName, String backupId) {
        removeCurrentContainer(containerName);
        docker.renameContainerCmd(backupId).withName(containerName).exec();
        docker.startContainerCmd(backupId).exec();
    }

    private void removeCurrentContainer(String containerName) {
        try {
            InspectContainerResponse current = docker.inspectContainerCmd(containerName).exec();
            docker.removeContainerCmd(current.getId()).withForce(true).withRemoveVolumes(false).exec();
        } catch (com.github.dockerjava.api.exception.NotFoundException ignored) {
            // No replacement container was created, so the backup can be restored directly.
        }
    }
}
