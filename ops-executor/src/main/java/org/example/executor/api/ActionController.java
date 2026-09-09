package org.example.executor.api;

import org.example.executor.core.DockerActionExecutor;
import org.example.executor.core.ExecutorActionJournal;
import org.example.executor.security.ExecutorRequestAuthenticator;
import org.example.ops.contract.ActionRequest;
import org.example.ops.contract.ActionResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1/actions")
public class ActionController {

    private final ExecutorRequestAuthenticator authenticator;
    private final DockerActionExecutor executor;
    private final ExecutorActionJournal journal;

    public ActionController(
            ExecutorRequestAuthenticator authenticator,
            DockerActionExecutor executor,
            ExecutorActionJournal journal) {
        this.authenticator = authenticator;
        this.executor = executor;
        this.journal = journal;
    }

    @PostMapping
    public ActionResult execute(@RequestBody ActionRequest request) {
        if (!authenticator.authenticate(request)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or replayed request");
        }
        synchronized (journal) {
            ExecutorActionJournal.BeginResult begin = journal.begin(request.requestId());
            if (begin.state() == ExecutorActionJournal.BeginState.COMPLETED) {
                return begin.result();
            }
            if (begin.state() == ExecutorActionJournal.BeginState.OUTCOME_UNKNOWN) {
                return ActionResult.outcomeUnknown(request);
            }
            ActionResult result = executor.execute(request);
            journal.complete(request.requestId(), result);
            return result;
        }
    }
}
