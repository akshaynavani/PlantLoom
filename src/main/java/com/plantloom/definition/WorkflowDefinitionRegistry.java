package com.plantloom.definition;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.plantloom.config.PlantLoomProperties;
import com.plantloom.domain.WorkflowDefinition;

import jakarta.annotation.PostConstruct;

/**
 * Loads, validates and serves workflow definitions. Definitions are read from YAML at startup
 * and can also be registered at runtime through the API.
 */
@Component
public class WorkflowDefinitionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorkflowDefinitionRegistry.class);

    private final Map<String, WorkflowDefinition> definitions = new ConcurrentHashMap<>();
    private final ObjectMapper yaml = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private final PlantLoomProperties properties;
    private final DefinitionValidator validator;

    public WorkflowDefinitionRegistry(PlantLoomProperties properties, DefinitionValidator validator) {
        this.properties = properties;
        this.validator = validator;
    }

    @PostConstruct
    void loadFromLocation() throws IOException {
        String location = properties.getDefinitionsLocation();
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources(location);
        for (Resource resource : resources) {
            try (InputStream in = resource.getInputStream()) {
                WorkflowDefinition definition = yaml.readValue(in, WorkflowDefinition.class);
                register(definition);
                log.info("Loaded workflow definition id={} version={} stages={} source={}",
                        definition.id(), definition.version(), definition.stages().size(), resource.getFilename());
            }
        }
        log.info("Workflow registry ready with {} definition(s) from {}", definitions.size(), location);
    }

    public WorkflowDefinition register(WorkflowDefinition definition) {
        validator.validate(definition);
        WorkflowDefinition previous = definitions.put(definition.id(), definition);
        if (previous != null && previous.version() > definition.version()) {
            log.warn("Definition {} replaced by an older version ({} -> {})",
                    definition.id(), previous.version(), definition.version());
        }
        return definition;
    }

    public WorkflowDefinition parseYaml(String content) throws IOException {
        return yaml.readValue(content, WorkflowDefinition.class);
    }

    public Optional<WorkflowDefinition> find(String id) {
        return Optional.ofNullable(definitions.get(id));
    }

    public WorkflowDefinition require(String id) {
        return find(id).orElseThrow(() -> new UnknownDefinitionException(id));
    }

    public Collection<WorkflowDefinition> all() {
        List<WorkflowDefinition> list = new java.util.ArrayList<>(definitions.values());
        list.sort(Comparator.comparing(WorkflowDefinition::id));
        return list;
    }
}
