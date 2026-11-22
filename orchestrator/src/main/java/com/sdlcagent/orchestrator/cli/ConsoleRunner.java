package com.sdlcagent.orchestrator.cli;

import com.sdlcagent.orchestrator.engine.RunState;
import com.sdlcagent.orchestrator.service.RunService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Console mode, enabled by one of:
 * <pre>
 *   --orchestrator.cli.scenario=greenfield|brownfield|ambiguous
 *   --orchestrator.cli.requirement-file=path/to/requirement.md
 *   --orchestrator.cli.resume=run-id
 * </pre>
 * Add {@code --spring.main.web-application-type=none} to skip the REST server. The process exits when the run
 * ends (0 = COMPLETED, 2 = safe-stopped/idle, 1 = error) unless {@code --orchestrator.cli.exit=false}.
 */
@Component
@ConditionalOnExpression("!'${orchestrator.cli.scenario:}${orchestrator.cli.requirement-file:}${orchestrator.cli.resume:}'.isEmpty()")
public class ConsoleRunner implements ApplicationRunner {
    private final RunService runs;
    private final ConfigurableApplicationContext ctx;
    private final Environment env;

    public ConsoleRunner(RunService runs, ConfigurableApplicationContext ctx, Environment env) {
        this.runs = runs;
        this.env = env;
        this.ctx = ctx;
    }

    @Override
    public void run(ApplicationArguments args) {
        String resume = first("orchestrator.cli.resume");
        String scenario = first("orchestrator.cli.scenario");
        String file = first("orchestrator.cli.requirement-file");
        ConsoleSession session = new ConsoleSession(runs,
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)), System.out);
        int code;
        try {
            String runId;
            if (resume != null) {
                runId = resume;
            } else if (file != null) {
                runId = runs.create(scenario == null ? "custom" : scenario, readFile(file), Scenarios.isGreenfield(scenario));
            } else {
                runId = runs.create(scenario, Scenarios.requirement(scenario), Scenarios.isGreenfield(scenario));
            }
            code = session.drive(runId).state == RunState.COMPLETED ? 0 : 2;
        } catch (RuntimeException e) {
            System.err.println("console run failed: " + e.getMessage());
            code = 1;
        }
        if (!"false".equals(first("orchestrator.cli.exit"))) {
            int springCode = SpringApplication.exit(ctx, () -> 0);
            System.exit(code != 0 ? code : springCode);
        }
    }

    private String first(String name) {
        String v = env.getProperty(name);
        return v == null || v.isBlank() ? null : v;
    }

    private static String readFile(String f) {
        try {
            return Files.readString(Path.of(f), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
