/*
 * Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.andes.kernel.disruptor.inbound;

import com.lmax.disruptor.EventHandler;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.andes.kernel.AndesChannel;
import org.wso2.andes.kernel.AndesMessage;
import org.wso2.andes.kernel.MessagingEngine;
import org.wso2.andes.kernel.slot.SlotMessageCounter;
import org.wso2.andes.metrics.MetricsConstants;
import org.wso2.andes.tools.utils.MessageTracer;
import org.wso2.carbon.metrics.manager.Level;
import org.wso2.carbon.metrics.manager.Meter;
import org.wso2.carbon.metrics.manager.MetricManager;

import java.util.List;

/**
 * State changes related to Andes for inbound events are handled through this handler
 */
public class StateEventHandler implements EventHandler<InboundEventContainer> {

    private static Log log = LogFactory.getLog(StateEventHandler.class);

    /**
     * reference to MessagingEngine
     */
    private final MessagingEngine messagingEngine;

    StateEventHandler(MessagingEngine messagingEngine) {
        this.messagingEngine = messagingEngine;
    }

    @Override
    public void onEvent(InboundEventContainer event, long sequence, boolean endOfBatch) throws Exception {


        if (log.isDebugEnabled()) {
            log.debug("[ sequence " + sequence + " ] Event received from disruptor. Event type: "
                    + event.eventInfo());
        }
        
        try {
            switch (event.getEventType()) {
                case MESSAGE_EVENT:
                    updateSlotsAndQueueCounts(event);
                    event.getChannel().recordRemovalFromBuffer(AndesChannel.getTotalChunkCount(event.getMessageList()));
                    break;
                case SAFE_ZONE_DECLARE_EVENT:
                    updateSlotDeleteSafeZone(event);
                    break;
                default:
                    event.updateState();
                    break;
            }

        } finally {
            // This is the final handler that visits the slot in ring buffer. Hence after processing is done clear the
            // slot so that in next iteration of the first event handler over the same slot won't find garbage from
            // previous iterations.
            event.clear();
        }
    }

    /**
     * Communicate this node's safe zone to the coordinator for evaluation.
     * @param event event
     */
    private void updateSlotDeleteSafeZone(InboundEventContainer event) {

        long currentSafeZoneVal = event.getSafeZoneLimit();
        SlotMessageCounter.getInstance().updateSafeZoneForNode(currentSafeZoneVal);
    }

    /**
     * Update slot message counters and queue counters
     *
     * @param eventContainer InboundEventContainer
     */
    public void updateSlotsAndQueueCounts(InboundEventContainer eventContainer) {

        List<AndesMessage> messageList = eventContainer.getMessageList();
        // update last message ID in slot message counter. When the slot is filled the last message
        // ID of the slot will be submitted to the slot manager by SlotMessageCounter
        SlotMessageCounter.getInstance().recordMetadataCountInSlot(messageList);

        for (AndesMessage message : messageList) {
            // For each message increment by 1. Underlying messaging engine will handle the increment destination
            // wise.
            messagingEngine.incrementQueueCount(message.getMetadata().getDestination(), 1);

            //Tracing Message
            MessageTracer.trace(message, MessageTracer.SLOT_INFO_UPDATED);

            eventContainer.pubAckHandler.ack(message.getMetadata());

            //Adding metrics meter for ack rate
            Meter ackMeter = MetricManager.meter(Level.INFO, this.getClass() + MetricsConstants.ACK_SENT_RATE);
            ackMeter.mark();
        }

        //We need to ack only once since, one publisher - multiple topics
        //Event container holds messages relevant to one message published
        //i.e retain messages the ack will be handled during the pre processing stage, therefore we need to ensure that
        // there're messages on the list
        if (messageList.size() > 0) {
            if (log.isDebugEnabled()) {
                log.debug("Acknowledging to the publisher " + eventContainer.getChannel());
            }
            eventContainer.pubAckHandler.ack(messageList.get(0).getMetadata());
        }

        if (log.isTraceEnabled()) {
            StringBuilder messageIds = new StringBuilder();
            for (AndesMessage message : messageList) {
                messageIds.append(message.getMetadata().getMessageID()).append(" , ");
            }
            log.debug("Messages STATE UPDATED: " + messageIds);
        }
    }
}