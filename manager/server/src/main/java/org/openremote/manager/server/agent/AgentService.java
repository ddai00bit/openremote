/*
 * Copyright 2016, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.server.agent;

import elemental.json.JsonObject;
import org.apache.camel.builder.RouteBuilder;
import org.openremote.agent3.protocol.Protocol;
import org.openremote.container.Container;
import org.openremote.container.ContainerService;
import org.openremote.container.message.MessageBrokerService;
import org.openremote.container.message.MessageBrokerSetupService;
import org.openremote.container.persistence.PersistenceEvent;
import org.openremote.manager.server.asset.AssetStorageService;
import org.openremote.manager.server.asset.ServerAsset;
import org.openremote.manager.server.datapoint.AssetDatapointService;
import org.openremote.model.AbstractValueTimestampHolder;
import org.openremote.model.AttributeEvent;
import org.openremote.model.AttributeRef;
import org.openremote.model.Pair;
import org.openremote.model.asset.*;
import org.openremote.model.asset.agent.AgentLink;
import org.openremote.model.asset.agent.ProtocolConfiguration;
import org.openremote.model.util.JsonUtil;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.openremote.agent3.protocol.Protocol.ACTUATOR_TOPIC;
import static org.openremote.container.persistence.PersistenceEvent.PERSISTENCE_TOPIC;
import static org.openremote.manager.server.asset.AssetPredicates.isPersistenceEventForAssetType;
import static org.openremote.manager.server.asset.AssetPredicates.isPersistenceEventForEntityType;
import static org.openremote.model.asset.AssetAttribute.attributesFromJson;
import static org.openremote.model.asset.AssetType.AGENT;
import static org.openremote.model.asset.agent.AgentLink.getAgentLink;

/**
 * Finds all {@link AssetType#AGENT} and {@link AssetType#THING} assets and starts the protocols for them.
 */
public class AgentService extends RouteBuilder implements ContainerService, Consumer<AssetState> {

    private static final Logger LOG = Logger.getLogger(AgentService.class.getName());
    protected Container container;
    protected AssetStorageService assetStorageService;
    protected AssetDatapointService assetDatapointService;
    protected MessageBrokerService messageBrokerService;
    protected static final Map<AttributeRef, AssetAttribute> protocolConfigurations = new HashMap<>();

    @Override
    public void init(Container container) throws Exception {
        this.container = container;
        assetStorageService = container.getService(AssetStorageService.class);
        assetDatapointService = container.getService(AssetDatapointService.class);
        messageBrokerService = container.getService(MessageBrokerService.class);
    }

    @Override
    public void start(Container container) throws Exception {
        container.getService(MessageBrokerSetupService.class).getContext().addRoutes(this);

        List<ServerAsset> agents = assetStorageService.findAll(new AssetQuery()
            .select(new AssetQuery.Select(true, false))
            .type(AssetType.AGENT));
        LOG.fine("Deploy all agents in all realms: " + agents.size());

        /// For all agents, go through their protocol configurations and find
        // assets that are linked to them, to create the binding on startup
        for (Asset agent : agents) {
            agent.getAttributesStream()
                .filter(ProtocolConfiguration::isProtocolConfiguration)
                .forEach(this::linkProtocol);
        }
    }

    @Override
    public void stop(Container container) throws Exception {
        synchronized (protocolConfigurations) {
            new ArrayList<>(protocolConfigurations.values())
                .forEach(this::unlinkProtocol);
        }
    }

    @Override
    public void configure() throws Exception {
        from(PERSISTENCE_TOPIC)
            .filter(isPersistenceEventForEntityType(Asset.class))
            .process(exchange -> {
                PersistenceEvent persistenceEvent = exchange.getIn().getBody(PersistenceEvent.class);
                Asset asset = (Asset) persistenceEvent.getEntity();
                if (isPersistenceEventForAssetType(AGENT).matches(exchange)) {
                    processAgentChange(asset, persistenceEvent);
                } else {
                    processAssetChange(asset, persistenceEvent);
                }
            });
    }

    /**
     * Looks for new, modified and obsolete protocol configurations and links / unlinks any associated
     * attributes
     */
    protected void processAgentChange(Asset agent, PersistenceEvent persistenceEvent) {
        LOG.finest("Processing agent persistence event: " + persistenceEvent.getCause());

        switch (persistenceEvent.getCause()) {
            case INSERT:

                agent.getAttributesStream()
                    .filter(ProtocolConfiguration::isProtocolConfiguration)
                    .forEach(this::linkProtocol);

                break;
            case UPDATE:

                // Check if any protocol config attributes have been added/removed or modified
                int attributesIndex = Arrays.asList(persistenceEvent.getPropertyNames()).indexOf("attributes");
                if (attributesIndex < 0) {
                    return;
                }

                // Attributes have possibly changed so need to compare old and new state to determine
                // which protocol configs are affected
                List<AssetAttribute> oldProtocolConfigurations =
                    attributesFromJson(
                        (JsonObject) persistenceEvent.getPreviousState()[attributesIndex],
                        agent.getId()
                    )
                        .filter(ProtocolConfiguration::isProtocolConfiguration)
                        .collect(Collectors.toList());

                List<AssetAttribute> newProtocolConfigurations =
                    attributesFromJson(
                        (JsonObject) persistenceEvent.getCurrentState()[attributesIndex],
                        agent.getId()
                    )
                        .filter(ProtocolConfiguration::isProtocolConfiguration)
                        .collect(Collectors.toList());

                // Compare protocol configurations by JSON value
                // Unlink protocols that are in oldConfigs but not in newConfigs
                oldProtocolConfigurations
                    .stream()
                    .filter(oldProtocolAttribute -> newProtocolConfigurations
                        .stream()
                        .noneMatch(newProtocolAttribute -> JsonUtil.equals(
                            oldProtocolAttribute.getJsonObject(),
                            newProtocolAttribute.getJsonObject())
                        )
                    )
                    .forEach(this::unlinkProtocol);

                // Link protocols that are in newConfigs but not in oldConfigs
                newProtocolConfigurations
                    .stream()
                    .filter(newProtocolAttribute -> oldProtocolConfigurations
                        .stream()
                        .noneMatch(oldProtocolAttribute ->
                            JsonUtil.equals(
                                oldProtocolAttribute.getJsonObject(),
                                newProtocolAttribute.getJsonObject()
                            )
                        )
                    ).forEach(this::linkProtocol);

                break;
            case DELETE:

                // Unlink any attributes that have an agent link to this agent
                agent.getAttributesStream()
                    .filter(ProtocolConfiguration::isProtocolConfiguration)
                    .forEach(this::unlinkProtocol);
                break;
        }
    }

    /**
     * Looks for new, modified and obsolete AGENT_LINK attributes and links / unlinks them
     * with the protocol
     */
    protected void processAssetChange(Asset asset, PersistenceEvent persistenceEvent) {
        LOG.finest("Processing asset persistence event: " + persistenceEvent.getCause());

        switch (persistenceEvent.getCause()) {
            case INSERT:

                // Asset insert persistence events can be fired before the agent insert persistence event
                // so need to check that all protocol configs exist - any that don't we will exclude here
                // and handle in agent insert

                // Link any AGENT_LINK attributes to the referenced protocol
                Map<AssetAttribute, List<AssetAttribute>> groupedAgentLinksAttributes =
                    getGroupedAgentLinkAttributes(asset.getAttributesStream(), attribute -> true);
                groupedAgentLinksAttributes.forEach(this::linkAttributes);

                break;
            case UPDATE:

                // Check if attributes of the asset have been modified
                int attributesIndex = Arrays.asList(persistenceEvent.getPropertyNames()).indexOf("attributes");
                if (attributesIndex < 0) {
                    return;
                }

                // Attributes have possibly changed so need to compare old and new state to determine any changes to
                // AGENT_LINK attributes
                List<AssetAttribute> oldAgentLinkedAttributes =
                    attributesFromJson(
                        (JsonObject) persistenceEvent.getPreviousState()[attributesIndex],
                        asset.getId()
                    )
                        .filter(AgentLink::hasAgentLink)
                        .collect(Collectors.toList());

                List<AssetAttribute> newAgentLinkedAttributes =
                    attributesFromJson(
                        (JsonObject) persistenceEvent.getCurrentState()[attributesIndex],
                        asset.getId()
                    )
                        .filter(AgentLink::hasAgentLink)
                        .collect(Collectors.toList());

                // Unlink thing attributes that are in old but not in new
                Stream<AssetAttribute> attributesToUnlink = oldAgentLinkedAttributes
                    .stream()
                    .filter(oldAgentLinkedAttribute -> newAgentLinkedAttributes
                        .stream()
                        .noneMatch(newAgentLinkedAttribute -> JsonUtil.equals( // Ignore the timestamp in comparison
                            oldAgentLinkedAttribute.getJsonObject(),
                            newAgentLinkedAttribute.getJsonObject(),
                            Collections.singletonList(AbstractValueTimestampHolder.VALUE_TIMESTAMP_FIELD_NAME))
                        )
                    );

                getGroupedAgentLinkAttributes(attributesToUnlink, attribute -> true)
                    .forEach(this::unlinkAttributes);

                // Link thing attributes that are in new but not in old
                Stream<AssetAttribute> attributesToLink = newAgentLinkedAttributes
                    .stream()
                    .filter(newThingAttribute -> oldAgentLinkedAttributes
                        .stream()
                        .noneMatch(oldThingAttribute -> JsonUtil.equals( // Ignore the timestamp in comparison
                            oldThingAttribute.getJsonObject(),
                            newThingAttribute.getJsonObject(),
                            Collections.singletonList(AbstractValueTimestampHolder.VALUE_TIMESTAMP_FIELD_NAME))
                        )
                    );

                getGroupedAgentLinkAttributes(attributesToLink, attribute -> true)
                    .forEach(this::linkAttributes);

                break;
            case DELETE: {

                // Unlink any AGENT_LINK attributes from the referenced protocol
                Map<AssetAttribute, List<AssetAttribute>> groupedAgentLinkAndProtocolAttributes =
                    getGroupedAgentLinkAttributes(asset.getAttributesStream(), attribute -> true);
                groupedAgentLinkAndProtocolAttributes
                    .forEach(
                        this::unlinkAttributes
                    );
                break;
            }
        }
    }

    protected void linkProtocol(AssetAttribute protocolConfiguration) {
        LOG.finest("Linking all attributes that use protocol attribute: " + protocolConfiguration);

        AttributeRef protocolAttributeRef = protocolConfiguration.getReferenceOrThrow();

        synchronized (protocolConfigurations) {
            // Store this protocol configuration for easy access later
            protocolConfigurations.put(protocolAttributeRef, protocolConfiguration);
        }

        // Get all assets that have attributes that use this protocol configuration
        List<ServerAsset> assets = assetStorageService.findAll(
            new AssetQuery()
                .select(new AssetQuery.Select(true, false))
                .attributeMeta(
                    new AssetQuery.AttributeRefPredicate(
                        AssetMeta.AGENT_LINK,
                        protocolAttributeRef.getEntityId(),
                        protocolAttributeRef.getAttributeName()
                    )
                )
        );

        assets.forEach(
            asset ->
                getGroupedAgentLinkAttributes(
                    asset.getAttributesStream(),
                    assetAttribute -> getAgentLink(assetAttribute)
                        .map(attributeRef -> attributeRef.equals(protocolAttributeRef))
                        .orElse(false)
                ).forEach(this::linkAttributes)
        );
    }

    protected void unlinkProtocol(AssetAttribute protocolConfiguration) {
        AttributeRef protocolAttributeRef = protocolConfiguration.getReferenceOrThrow();

        // Get all assets that have attributes that use this protocol configuration
        List<ServerAsset> assets = assetStorageService.findAll(
            new AssetQuery()
                .select(new AssetQuery.Select(true, false))
                .attributeMeta(
                    new AssetQuery.AttributeRefPredicate(
                        AssetMeta.AGENT_LINK,
                        protocolAttributeRef.getEntityId(),
                        protocolAttributeRef.getAttributeName()
                    )
                )
        );

        assets.forEach(
            asset ->
                getGroupedAgentLinkAttributes(
                    asset.getAttributesStream(),
                    assetAttribute -> getAgentLink(assetAttribute)
                        .map(attributeRef -> attributeRef.equals(protocolAttributeRef))
                        .orElse(false)
                ).forEach(this::unlinkAttributes)
        );

        synchronized (protocolConfigurations) {
            protocolConfigurations.remove(protocolAttributeRef);
        }
    }

    protected Protocol getProtocol(AssetAttribute protocolConfiguration) {
        Collection<Protocol> protocols = container.getServices(Protocol.class);
        return protocols
            .stream()
            .filter(protocol -> {
                if (protocol.getProtocolName() == null || protocol.getProtocolName().length() == 0)
                    throw new IllegalStateException("Protocol can't have empty name: " + protocol.getClass());
                return protocol.getProtocolName().equals(protocolConfiguration.getValueAsString().orElse(null));
            })
            .findFirst()
            .orElse(null);
    }

    protected void linkAttributes(AssetAttribute protocolConfiguration, Collection<AssetAttribute> attributes) {
        Protocol protocol = getProtocol(protocolConfiguration);

        if (protocol == null) {
            LOG.severe("Cannot link protocol attributes as protocol is null");
            return;
        }

        try {
            LOG.finest("Linking protocol attributes to: " + protocol.getProtocolName());
            protocol.linkAttributes(attributes, protocolConfiguration);
        } catch (Exception ex) {
            // TODO: Better error handling?
            LOG.log(Level.WARNING, "Ignoring error on linking attributes to protocol: " + protocol.getProtocolName(), ex);
        }
    }

    protected void unlinkAttributes(AssetAttribute protocolConfiguration, Collection<AssetAttribute> attributes) {
        Protocol protocol = getProtocol(protocolConfiguration);

        if (protocol == null) {
            LOG.severe("Cannot link protocol attributes as protocol is null");
            return;
        }

        try {
            LOG.finest("Unlinking protocol attributes form: " + protocol.getProtocolName());
            protocol.unlinkAttributes(attributes, protocolConfiguration);
        } catch (Exception ex) {
            // TODO: Better error handling?
            LOG.log(Level.WARNING, "Ignoring error on unlinking attributes from protocol: " + protocol.getProtocolName(), ex);
        }
    }

    /**
     * If this update is not for an asset of type THING or it has been initiated by
     * a protocol then we ignore it.
     * <p>
     * Otherwise we push the update to the protocol to handle and prevent any further
     * processing of this event by the processing chain, the protocol should raise
     * sensor updates as required (i.e. the protocol is responsible for synchronising state)
     */
    @Override
    public void accept(AssetState assetState) {
        // If update was initiated by a protocol ignore it
        if (assetState.isNorthbound()) {
            LOG.fine("Ignoring as it came from a protocol:" + assetState);
            return;
        }

        AgentLink.getAgentLink(assetState.getAttribute())
            .map(ref ->
                getProtocolConfiguration(ref)
                    .orElseGet(() -> {
                        LOG.warning("Cannot process as agent link is invalid:" + assetState);
                        assetState.setError(new RuntimeException("Attribute has an invalid agent link:" + assetState.getAttribute()));
                        assetState.setProcessingStatus(AssetState.ProcessingStatus.ERROR);
                        return null;
                    })
            )
            .map(protocolConfiguration -> {
                // Its' a send to actuator - push the update to the protocol
                LOG.fine("Processing: " + assetState);

                Optional<AttributeEvent> event = assetState
                    .getAttribute()
                    .getStateEvent();

                if (event.isPresent()) {
                    messageBrokerService.getProducerTemplate().sendBody(
                        ACTUATOR_TOPIC,
                        event.get()
                    );
                } else {
                    LOG.warning("Attribute doesn't have a valid attribute event: " + assetState);
                }

                assetState.setProcessingStatus(AssetState.ProcessingStatus.HANDLED);
                return protocolConfiguration;
            })
            .orElseGet(() -> {
                // This is just a non protocol attribute so allow the processing to continue
                LOG.fine("Ignoring as it is not for an attribute linked to an agent:" + assetState);
                return null;
            });
    }

    /**
     * Gets all agent link attributes and their linked protocol configuration and groups them by Protocol Configuration
     */
    @SuppressWarnings("ConstantConditions")
    protected Map<AssetAttribute, List<AssetAttribute>> getGroupedAgentLinkAttributes(Stream<AssetAttribute> attributes,
                                                                                      Predicate<AssetAttribute> filter) {
        Map<AssetAttribute, List<AssetAttribute>> result = new HashMap<>();
        attributes
            .filter(AgentLink::hasAgentLink)
            .filter(filter)
            .map(attribute -> new Pair<>(attribute, getAgentLink(attribute)))
            .filter(pair -> pair.value.isPresent())
            .map(pair -> new Pair<>(pair.key, getProtocolConfiguration(pair.value.get())))
            .filter(pair -> pair.value.isPresent())
            .forEach(pair -> result.computeIfAbsent(pair.value.get(), newProtocolConfiguration -> new ArrayList<>())
                .add(pair.key)
            );
        return result;
    }

    public String toString() {
        return getClass().getSimpleName() + "{" +
            '}';
    }

    public static Optional<AssetAttribute> getProtocolConfiguration(AttributeRef protocolRef) {
        synchronized (protocolConfigurations) {
            return Optional.ofNullable(protocolConfigurations.get(protocolRef));
        }
    }
}