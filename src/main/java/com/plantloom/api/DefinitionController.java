package com.plantloom.api;

import java.io.IOException;
import java.util.Collection;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.plantloom.definition.WorkflowDefinitionRegistry;
import com.plantloom.domain.WorkflowDefinition;

@RestController
@RequestMapping("/api/definitions")
public class DefinitionController {

    private final WorkflowDefinitionRegistry registry;

    public DefinitionController(WorkflowDefinitionRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Collection<WorkflowDefinition> list() {
        return registry.all();
    }

    @GetMapping("/{id}")
    public WorkflowDefinition get(@PathVariable String id) {
        return registry.require(id);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowDefinition registerJson(@RequestBody WorkflowDefinition definition) {
        return registry.register(definition);
    }

    /** Accepts the same YAML format as the files under {@code resources/workflows}. */
    @PostMapping(consumes = {"application/yaml", "application/x-yaml", MediaType.TEXT_PLAIN_VALUE})
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowDefinition registerYaml(@RequestBody String yaml) throws IOException {
        return registry.register(registry.parseYaml(yaml));
    }
}
