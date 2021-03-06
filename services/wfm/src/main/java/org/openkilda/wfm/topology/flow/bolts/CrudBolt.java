/* Copyright 2017 Telstra Open Source
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.openkilda.wfm.topology.flow.bolts;

import static java.lang.String.format;
import static org.openkilda.messaging.Utils.MAPPER;

import com.google.common.annotations.VisibleForTesting;
import org.apache.storm.state.InMemoryKeyValueState;
import org.apache.storm.task.OutputCollector;
import org.apache.storm.task.TopologyContext;
import org.apache.storm.topology.OutputFieldsDeclarer;
import org.apache.storm.topology.base.BaseStatefulBolt;
import org.apache.storm.tuple.Tuple;
import org.apache.storm.tuple.Values;
import org.openkilda.messaging.Destination;
import org.openkilda.messaging.Message;
import org.openkilda.messaging.Utils;
import org.openkilda.messaging.command.CommandMessage;
import org.openkilda.messaging.command.flow.FlowCreateRequest;
import org.openkilda.messaging.command.flow.FlowRerouteRequest;
import org.openkilda.messaging.command.flow.FlowRestoreRequest;
import org.openkilda.messaging.command.flow.FlowUpdateRequest;
import org.openkilda.messaging.ctrl.AbstractDumpState;
import org.openkilda.messaging.ctrl.state.CrudBoltState;
import org.openkilda.messaging.ctrl.state.FlowDump;
import org.openkilda.messaging.error.CacheException;
import org.openkilda.messaging.error.ErrorData;
import org.openkilda.messaging.error.ErrorMessage;
import org.openkilda.messaging.error.ErrorType;
import org.openkilda.messaging.error.MessageException;
import org.openkilda.messaging.info.InfoMessage;
import org.openkilda.messaging.info.event.PathInfoData;
import org.openkilda.messaging.info.flow.*;
import org.openkilda.messaging.model.Flow;
import org.openkilda.messaging.model.ImmutablePair;
import org.openkilda.messaging.payload.flow.FlowCacheSyncResults;
import org.openkilda.messaging.payload.flow.FlowIdStatusPayload;
import org.openkilda.messaging.payload.flow.FlowState;
import org.openkilda.pce.cache.FlowCache;
import org.openkilda.pce.cache.ResourceCache;
import org.openkilda.pce.provider.Auth;
import org.openkilda.pce.provider.FlowInfo;
import org.openkilda.pce.provider.PathComputer;
import org.openkilda.pce.provider.PathComputer.Strategy;
import org.openkilda.pce.provider.UnroutablePathException;
import org.openkilda.wfm.ctrl.CtrlAction;
import org.openkilda.wfm.ctrl.ICtrlBolt;
import org.openkilda.wfm.topology.AbstractTopology;
import org.openkilda.wfm.topology.flow.ComponentType;
import org.openkilda.wfm.topology.flow.FlowTopology;
import org.openkilda.wfm.topology.flow.StreamType;
import org.openkilda.wfm.topology.flow.validation.FlowValidationException;
import org.openkilda.wfm.topology.flow.validation.FlowValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class CrudBolt
        extends BaseStatefulBolt<InMemoryKeyValueState<String, FlowCache>>
        implements ICtrlBolt {

    public static final String STREAM_ID_CTRL = "ctrl";

    /**
     * The logger.
     */
    private static final Logger logger = LoggerFactory.getLogger(CrudBolt.class);

    /**
     * Flow cache key.
     */
    private static final String FLOW_CACHE = "flow";

    /**
     * Path computation instance.
     */
    private PathComputer pathComputer;
    private final Auth pathComputerAuth;

    /**
     * Flows state.
     */
    private InMemoryKeyValueState<String, FlowCache> caches;

    private TopologyContext context;
    private OutputCollector outputCollector;

    /**
     * Flow cache.
     */
    private FlowCache flowCache;

    /**
     * Instance constructor.
     *
     * @param pathComputerAuth {@link Auth} instance
     */
    public CrudBolt(Auth pathComputerAuth) {
        this.pathComputerAuth = pathComputerAuth;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void initState(InMemoryKeyValueState<String, FlowCache> state) {
        this.caches = state;

        // TODO - do we have to use InMemoryKeyValue, or is there some other InMemory option?
        //  The reason for the qestion .. we are only putting in one object.
        flowCache = state.get(FLOW_CACHE);
        if (flowCache == null) {
            flowCache = new FlowCache();
            this.caches.put(FLOW_CACHE, flowCache);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void declareOutputFields(OutputFieldsDeclarer outputFieldsDeclarer) {
        outputFieldsDeclarer.declareStream(StreamType.CREATE.toString(), AbstractTopology.fieldMessage);
        outputFieldsDeclarer.declareStream(StreamType.UPDATE.toString(), AbstractTopology.fieldMessage);
        outputFieldsDeclarer.declareStream(StreamType.DELETE.toString(), AbstractTopology.fieldMessage);
        outputFieldsDeclarer.declareStream(StreamType.STATUS.toString(), AbstractTopology.fieldMessage);
        outputFieldsDeclarer.declareStream(StreamType.RESPONSE.toString(), AbstractTopology.fieldMessage);
        outputFieldsDeclarer.declareStream(StreamType.ERROR.toString(), FlowTopology.fieldsMessageErrorType);
        // FIXME(dbogun): use proper tuple format
        outputFieldsDeclarer.declareStream(STREAM_ID_CTRL, AbstractTopology.fieldMessage);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void prepare(Map map, TopologyContext topologyContext, OutputCollector outputCollector) {
        this.context = topologyContext;
        this.outputCollector = outputCollector;

        pathComputer = pathComputerAuth.connect();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute(Tuple tuple) {

        if (CtrlAction.boltHandlerEntrance(this, tuple))
            return;

        logger.trace("Flow Cache before: {}", flowCache);

        ComponentType componentId = ComponentType.valueOf(tuple.getSourceComponent());
        StreamType streamId = StreamType.valueOf(tuple.getSourceStreamId());
        String flowId = tuple.getStringByField(Utils.FLOW_ID);
        String correlationId = Utils.DEFAULT_CORRELATION_ID;

        try {
            logger.debug("Request tuple={}", tuple);

            switch (componentId) {

                case SPLITTER_BOLT:
                    Message msg = (Message) tuple.getValueByField(AbstractTopology.MESSAGE_FIELD);
                    correlationId = msg.getCorrelationId();

                    CommandMessage cmsg = (msg instanceof CommandMessage) ? (CommandMessage) msg : null;
                    InfoMessage imsg = (msg instanceof InfoMessage) ? (InfoMessage) msg : null;

                    logger.info("Flow request: {}={}, {}={}, component={}, stream={}",
                            Utils.CORRELATION_ID, correlationId, Utils.FLOW_ID, flowId, componentId, streamId);

                    switch (streamId) {
                        case CREATE:
                            handleCreateRequest(cmsg, tuple);
                            break;
                        case UPDATE:
                            handleUpdateRequest(cmsg, tuple);
                            break;
                        case DELETE:
                            handleDeleteRequest(flowId, cmsg, tuple);
                            break;
                        case PUSH:
                            handlePushRequest(flowId, imsg, tuple);
                            break;
                        case UNPUSH:
                            handleUnpushRequest(flowId, imsg, tuple);
                            break;
                        case PATH:
                            handlePathRequest(flowId, cmsg, tuple);
                            break;
                        case RESTORE:
                            handleRestoreRequest(cmsg, tuple);
                            break;
                        case REROUTE:
                            handleRerouteRequest(cmsg, tuple);
                            break;
                        case STATUS:
                            handleStatusRequest(flowId, cmsg, tuple);
                            break;
                        case CACHE_SYNC:
                            handleCacheSyncRequest(cmsg, tuple);
                            break;
                        case READ:
                            if (flowId != null) {
                                handleReadRequest(flowId, cmsg, tuple);
                            } else {
                                handleDumpRequest(cmsg, tuple);
                            }
                            break;
                        default:

                            logger.debug("Unexpected stream: component={}, stream={}", componentId, streamId);
                            break;
                    }
                    break;

                case SPEAKER_BOLT:
                case TRANSACTION_BOLT:

                    FlowState newStatus = (FlowState) tuple.getValueByField(FlowTopology.STATUS_FIELD);

                    logger.info("Flow {} status {}: component={}, stream={}", flowId, newStatus, componentId, streamId);

                    switch (streamId) {
                        case STATUS:
                            handleStateRequest(flowId, newStatus, tuple);
                            break;
                        default:
                            logger.debug("Unexpected stream: component={}, stream={}", componentId, streamId);
                            break;
                    }
                    break;

                case TOPOLOGY_ENGINE_BOLT:

                    ErrorMessage errorMessage = (ErrorMessage) tuple.getValueByField(AbstractTopology.MESSAGE_FIELD);

                    logger.info("Flow {} error: component={}, stream={}", flowId, componentId, streamId);

                    switch (streamId) {
                        case STATUS:
                            handleErrorRequest(flowId, errorMessage, tuple);
                            break;
                        default:
                            logger.debug("Unexpected stream: component={}, stream={}", componentId, streamId);
                            break;
                    }
                    break;

                default:
                    logger.debug("Unexpected component: {}", componentId);
                    break;
            }
        } catch (CacheException exception) {
            String logMessage = format("%s: %s", exception.getErrorMessage(), exception.getErrorDescription());
            logger.error("{}, {}={}, {}={}, component={}, stream={}", logMessage, Utils.CORRELATION_ID,
                    correlationId, Utils.FLOW_ID, flowId, componentId, streamId, exception);

            ErrorMessage errorMessage = buildErrorMessage(correlationId, exception.getErrorType(),
                    logMessage, componentId.toString().toLowerCase());

            Values error = new Values(errorMessage, exception.getErrorType());
            outputCollector.emit(StreamType.ERROR.toString(), tuple, error);

        } catch (IOException exception) {
            logger.error("Could not deserialize message {}", tuple, exception);

        } finally {
            logger.debug("Command message ack: component={}, stream={}, tuple={}",
                    tuple.getSourceComponent(), tuple.getSourceStreamId(), tuple);

            outputCollector.ack(tuple);
        }

        logger.trace("Flow Cache after: {}", flowCache);
    }

    private void handleCacheSyncRequest(CommandMessage message, Tuple tuple) throws IOException {
        logger.info("CACHE SYNCE: {}", message);

        // NB: This is going to be a "bulky" operation - get all flows from DB, and synchronize
        //      with the cache.


        List<String> droppedFlows = new ArrayList<>();
        List<String> addedFlows = new ArrayList<>();
        List<String> modifiedFlows = new ArrayList<>();
        List<String> unchangedFlows = new ArrayList<>();

        List<FlowInfo> flowInfos = pathComputer.getFlowInfo();

        // Instead of determining left/right .. store based on flowid_& cookie
        HashMap<String,FlowInfo> flowToInfo = new HashMap<>();
        for (FlowInfo fi : flowInfos){
            flowToInfo.put(fi.getFlowId()+fi.getCookie(),fi);
        }

        // We first look at comparing what is in the DB to what is in the Cache
        for (FlowInfo fi : flowInfos){
            String flowid = fi.getFlowId();
            if (flowCache.cacheContainsFlow(flowid)){
                // TODO: better, more holistic comparison
                // TODO: if the flow is modified, then just leverage drop / add primitives.
                // TODO: Ensure that the DB is always the source of truth - cache and db ops part of transaction.
                // Need to compare both sides
                ImmutablePair<Flow,Flow> fc = flowCache.getFlow(flowid);

                int count = modifiedFlows.size();
                if (fi.getCookie() != fc.left.getCookie() && fi.getCookie() != fc.right.getCookie())
                    modifiedFlows.add("cookie: " + flowid + ":" + fi.getCookie() + ":" + fc.left.getCookie() + ":" + fc.right.getCookie());
                if (fi.getMeterId() != fc.left.getMeterId() && fi.getMeterId() != fc.right.getMeterId())
                    modifiedFlows.add("meter: " + flowid + ":" + fi.getMeterId() + ":" + fc.left.getMeterId() + ":" + fc.right.getMeterId());
                if (fi.getTransitVlanId() != fc.left.getTransitVlan() && fi.getTransitVlanId() != fc.right.getTransitVlan())
                    modifiedFlows.add("transit: " + flowid + ":" + fi.getTransitVlanId() + ":" + fc.left.getTransitVlan() + ":" + fc.right.getTransitVlan());
                if (!fi.getSrcSwitchId().equals(fc.left.getSourceSwitch()) && !fi.getSrcSwitchId().equals(fc.right.getSourceSwitch()))
                    modifiedFlows.add("switch: " + flowid + "|" + fi.getSrcSwitchId() + "|" + fc.left.getSourceSwitch() + "|" + fc.right.getSourceSwitch());
                if (count == modifiedFlows.size())
                    unchangedFlows.add(flowid);
            } else {
                // TODO: need to get the flow from the DB and add it properly
                addedFlows.add(flowid);
            }
        }

        // Now we see if the cache holds things not in the DB
        for (ImmutablePair<Flow, Flow> flow : flowCache.dumpFlows()){
            String key = flow.left.getFlowId() + flow.left.getCookie();
            // compare the left .. if it is in, then check the right .. o/w remove it (no need to check right
            if (!flowToInfo.containsKey(key)){
/* (carmine) - This code is to drop the flow from the cache since it isn't in the DB
 *  But - the user can just as easily call delete in the NB API .. which should do the right thing.
 *  So, for now, just add the flow id.
 */
//                String removedFlow = flowCache.removeFlow(flow.left.getFlowId()).toString();
//                String asJson = MAPPER.writeValueAsString(removedFlow);
//                droppedFlows.add(asJson);
                droppedFlows.add(flow.left.getFlowId());
            } else {
                key = flow.right.getFlowId() + flow.right.getCookie();
                if (!flowToInfo.containsKey(key)) {
// (carmine) - same comment..
//                    String removedFlow = flowCache.removeFlow(flow.left.getFlowId()).toString();
//                    String asJson = MAPPER.writeValueAsString(removedFlow);
//                    droppedFlows.add(asJson);
                    droppedFlows.add(flow.right.getFlowId());
                }
            }
        }

        FlowCacheSyncResults results = new FlowCacheSyncResults(
                droppedFlows.toArray(new String[0]), addedFlows.toArray(new String[0]),
                modifiedFlows.toArray(new String[0]), unchangedFlows.toArray(new String[0]));
        Values northbound = new Values(new InfoMessage(new FlowCacheSyncResponse(results),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }


    private void handlePushRequest(String flowId, InfoMessage message, Tuple tuple) throws IOException {
        logger.info("PUSH flow: {} :: {}", flowId, message);
        FlowInfoData fid = (FlowInfoData) message.getData();
        ImmutablePair<Flow,Flow> flow = fid.getPayload();

        flowCache.pushFlow(flow);

        Values northbound = new Values(new InfoMessage(new FlowStatusResponse(new FlowIdStatusPayload(flowId, FlowState.UP)),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    private void handleUnpushRequest(String flowId, InfoMessage message, Tuple tuple) throws IOException {
        logger.info("UNPUSH flow: {} :: {}", flowId, message);
        FlowInfoData fid = (FlowInfoData) message.getData();

        flowCache.deleteFlow(flowId);

        Values northbound = new Values(new InfoMessage(new FlowStatusResponse(new FlowIdStatusPayload(flowId, FlowState.DOWN)),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }


    private void handleDeleteRequest(String flowId, CommandMessage message, Tuple tuple) throws IOException {
        ImmutablePair<Flow, Flow> flow = flowCache.deleteFlow(flowId);

        logger.info("Deleted flow: {}", flow);

        FlowInfoData data = new FlowInfoData(flowId, flow, FlowOperation.DELETE, message.getCorrelationId());
        InfoMessage infoMessage = new InfoMessage(data, System.currentTimeMillis(), message.getCorrelationId());
        Values topology = new Values(MAPPER.writeValueAsString(infoMessage));
        outputCollector.emit(StreamType.DELETE.toString(), tuple, topology);

        Values northbound = new Values(new InfoMessage(new FlowResponse(buildFlowResponse(flow)),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    private void handleCreateRequest(CommandMessage message, Tuple tuple) throws IOException {
        Flow requestedFlow = ((FlowCreateRequest) message.getData()).getPayload();

        ImmutablePair<PathInfoData, PathInfoData> path;
        try {
            new FlowValidator(flowCache).checkFlowForEndpointConflicts(requestedFlow);

            path = pathComputer.getPath(requestedFlow, Strategy.COST);
            logger.info("Created flow path: {}", path);

        } catch (FlowValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.CREATION_FAILURE, "Could not create flow", e.getMessage());
        } catch (UnroutablePathException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.CREATION_FAILURE, "Could not create flow", "Path was not found");
        }

        ImmutablePair<Flow, Flow> flow = flowCache.createFlow(requestedFlow, path);
        logger.info("Created flow: {}", flow);

        FlowInfoData data = new FlowInfoData(requestedFlow.getFlowId(), flow, FlowOperation.CREATE,
                message.getCorrelationId());
        InfoMessage infoMessage = new InfoMessage(data, System.currentTimeMillis(), message.getCorrelationId());
        Values topology = new Values(MAPPER.writeValueAsString(infoMessage));
        outputCollector.emit(StreamType.CREATE.toString(), tuple, topology);

        Values northbound = new Values(new InfoMessage(new FlowResponse(buildFlowResponse(flow)),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    private void handleRerouteRequest(CommandMessage message, Tuple tuple) throws IOException {
        FlowRerouteRequest request = (FlowRerouteRequest) message.getData();
        Flow requestedFlow = request.getPayload();
        final String flowId = requestedFlow.getFlowId();
        ImmutablePair<Flow, Flow> flow;
        logger.debug("Handling reroute request with correlationId {}", message.getCorrelationId());

        switch (request.getOperation()) {

            case UPDATE:
                flow = flowCache.getFlow(flowId);

                try {
                    ImmutablePair<PathInfoData, PathInfoData> path =
                            pathComputer.getPath(flow.getLeft(), Strategy.COST);
                    logger.info("Rerouted flow path: {}", path);
                    //no need to emit changes if path wasn't changed and flow is active.
                    if (!path.getLeft().equals(flow.getLeft().getFlowPath()) || !isFlowActive(flow)) {
                        flow.getLeft().setState(FlowState.DOWN);
                        flow.getRight().setState(FlowState.DOWN);

                        flow = flowCache.updateFlow(flow.getLeft(), path);
                        logger.info("Rerouted flow: {}", flow);

                        FlowInfoData data = new FlowInfoData(flowId, flow, FlowOperation.UPDATE,
                                message.getCorrelationId());
                        InfoMessage infoMessage = new InfoMessage(data, System.currentTimeMillis(),
                                message.getCorrelationId());
                        Values topology = new Values(MAPPER.writeValueAsString(infoMessage));
                        outputCollector.emit(StreamType.UPDATE.toString(), tuple, topology);
                    } else {
                        logger.debug("Reroute was unsuccessful: can't find new path");
                    }

                    logger.debug("Sending response to NB. Correlation id {}", message.getCorrelationId());
                    Values response = new Values(new InfoMessage(new FlowPathResponse(flow.left.getFlowPath()),
                            message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
                    outputCollector.emit(StreamType.RESPONSE.toString(), tuple, response);
                } catch (UnroutablePathException e) {
                    flow.getLeft().setState(FlowState.DOWN);
                    flow.getRight().setState(FlowState.DOWN);
                    throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                            ErrorType.UPDATE_FAILURE, "Could not reroute flow", "Path was not found");
                }
                break;

            case CREATE:
                flow = flowCache.getFlow(flowId);
                logger.info("State flow: {}={}", flow.getLeft().getFlowId(), FlowState.UP);
                flow.getLeft().setState(FlowState.UP);
                flow.getRight().setState(FlowState.UP);
                break;

            case DELETE:
                flow = flowCache.getFlow(flowId);
                logger.info("State flow: {}={}", flow.getLeft().getFlowId(), FlowState.DOWN);
                flow.getLeft().setState(FlowState.DOWN);
                flow.getRight().setState(FlowState.DOWN);
                break;

            default:
                logger.warn("Flow {} undefined reroute operation", request.getOperation());
                break;
        }
    }

    private void handleRestoreRequest(CommandMessage message, Tuple tuple) throws IOException {
        ImmutablePair<Flow, Flow> requestedFlow = ((FlowRestoreRequest) message.getData()).getPayload();

        try {
            ImmutablePair<PathInfoData, PathInfoData> path = pathComputer.getPath(requestedFlow.getLeft(), Strategy.COST);
            logger.info("Restored flow path: {}", path);

            ImmutablePair<Flow, Flow> flow;
            if (flowCache.cacheContainsFlow(requestedFlow.getLeft().getFlowId())) {
                flow = flowCache.updateFlow(requestedFlow, path);
            } else {
                flow = flowCache.createFlow(requestedFlow, path);
            }
            logger.info("Restored flow: {}", flow);

            Values topology = new Values(Utils.MAPPER.writeValueAsString(
                    new FlowInfoData(requestedFlow.getLeft().getFlowId(), flow,
                            FlowOperation.UPDATE, message.getCorrelationId())));
            outputCollector.emit(StreamType.UPDATE.toString(), tuple, topology);
        } catch (UnroutablePathException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.CREATION_FAILURE, "Could not restore flow", "Path was not found");
        }
    }

    private void handleUpdateRequest(CommandMessage message, Tuple tuple) throws IOException {
        Flow requestedFlow = ((FlowUpdateRequest) message.getData()).getPayload();

        ImmutablePair<PathInfoData, PathInfoData> path;
        try {
            new FlowValidator(flowCache).checkFlowForEndpointConflicts(requestedFlow);

            path = pathComputer.getPath(requestedFlow, Strategy.COST);
            logger.info("Updated flow path: {}", path);

        } catch (FlowValidationException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.UPDATE_FAILURE, "Could not update flow", e.getMessage());
        } catch (UnroutablePathException e) {
            throw new MessageException(message.getCorrelationId(), System.currentTimeMillis(),
                    ErrorType.UPDATE_FAILURE, "Could not update flow", "Path was not found");
        }

        ImmutablePair<Flow, Flow> flow = flowCache.updateFlow(requestedFlow, path);
        logger.info("Updated flow: {}", flow);

        FlowInfoData data = new FlowInfoData(requestedFlow.getFlowId(), flow, FlowOperation.UPDATE,
                message.getCorrelationId());
        InfoMessage infoMessage = new InfoMessage(data, System.currentTimeMillis(), message.getCorrelationId());
        Values topology = new Values(MAPPER.writeValueAsString(infoMessage));
        outputCollector.emit(StreamType.UPDATE.toString(), tuple, topology);

        Values northbound = new Values(new InfoMessage(new FlowResponse(buildFlowResponse(flow)),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    private void handleDumpRequest(CommandMessage message, Tuple tuple) {
        List<Flow> flows = flowCache.dumpFlows().stream().map(this::buildFlowResponse).collect(Collectors.toList());

        logger.info("Dump flows: {}", flows);

        Values northbound = new Values(new InfoMessage(new FlowsResponse(flows),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    private void handleReadRequest(String flowId, CommandMessage message, Tuple tuple) {
        ImmutablePair<Flow, Flow> flow = flowCache.getFlow(flowId);

        logger.info("Got flow: {}", flow);

        Values northbound = new Values(new InfoMessage(new FlowResponse(buildFlowResponse(flow)),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    private void handlePathRequest(String flowId, CommandMessage message, Tuple tuple) throws IOException {
        ImmutablePair<Flow, Flow> flow = flowCache.getFlow(flowId);

        logger.info("Path flow: {}", flow);

        Values northbound = new Values(new InfoMessage(new FlowPathResponse(flow.left.getFlowPath()),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    private void handleStatusRequest(String flowId, CommandMessage message, Tuple tuple) throws IOException {
        ImmutablePair<Flow, Flow> flow = flowCache.getFlow(flowId);
        FlowState status = flow.getLeft().getState();

        logger.info("Status flow: {}={}", flowId, status);

        Values northbound = new Values(new InfoMessage(new FlowStatusResponse(new FlowIdStatusPayload(flowId, status)),
                message.getTimestamp(), message.getCorrelationId(), Destination.NORTHBOUND));
        outputCollector.emit(StreamType.RESPONSE.toString(), tuple, northbound);
    }

    /**
     * This method changes the state of the Flow. It sets the state of both left and right to the
     * same state.
     *
     * It is currently called from 2 places - a failed update (set flow to DOWN), and a STATUS
     * update from the TransactionBolt.
     */
    private void handleStateRequest(String flowId, FlowState state, Tuple tuple) throws IOException {
        ImmutablePair<Flow, Flow> flow = flowCache.getFlow(flowId);
        logger.info("State flow: {}={}", flowId, state);
        flow.getLeft().setState(state);
        flow.getRight().setState(state);

        final String correlationId = UUID.randomUUID().toString();
        FlowInfoData data = new FlowInfoData(flowId, flow, FlowOperation.STATE, correlationId);
        InfoMessage infoMessage = new InfoMessage(data, System.currentTimeMillis(), correlationId);

        Values topology = new Values(Utils.MAPPER.writeValueAsString(infoMessage));
        outputCollector.emit(StreamType.STATUS.toString(), tuple, topology);

    }

    private void handleErrorRequest(String flowId, ErrorMessage message, Tuple tuple) throws IOException {
        ErrorType errorType = message.getData().getErrorType();
        message.getData().setErrorDescription("topology-engine internal error");

        logger.info("Flow {} {} failure", errorType, flowId);

        switch (errorType) {
            case CREATION_FAILURE:
                flowCache.removeFlow(flowId);
                break;

            case UPDATE_FAILURE:
                handleStateRequest(flowId, FlowState.DOWN, tuple);
                break;

            case DELETION_FAILURE:
                break;

            case INTERNAL_ERROR:
                break;

            default:
                logger.warn("Flow {} undefined failure", flowId);

        }

        Values error = new Values(message, errorType);
        outputCollector.emit(StreamType.ERROR.toString(), tuple, error);
    }

    /**
     * Builds response flow.
     *
     * @param flow cache flow
     * @return response flow model
     */
    private Flow buildFlowResponse(ImmutablePair<Flow, Flow> flow) {
        Flow response = new Flow(flow.left);
        response.setCookie(response.getCookie() & ResourceCache.FLOW_COOKIE_VALUE_MASK);
        return response;
    }

    private ErrorMessage buildErrorMessage(String correlationId, ErrorType type, String message, String description) {
        return new ErrorMessage(new ErrorData(type, message, description),
                System.currentTimeMillis(), correlationId, Destination.NORTHBOUND);
    }

    private boolean isFlowActive(ImmutablePair<Flow, Flow> flowPair) {
        return flowPair.getLeft().getState().isActive() && flowPair.getRight().getState().isActive();
    }

    @Override
    public AbstractDumpState dumpState() {
        FlowDump flowDump = new FlowDump(flowCache.dumpFlows());
        return new CrudBoltState(flowDump);
    }

    @VisibleForTesting
    @Override
    public void clearState() {
        logger.info("State clear request from test");
        initState(new InMemoryKeyValueState<>());
    }


    @Override
    public String getCtrlStreamId() {
        return STREAM_ID_CTRL;
    }

    @Override
    public TopologyContext getContext() {
        return context;
    }

    @Override
    public OutputCollector getOutput() {
        return outputCollector;
    }
}
