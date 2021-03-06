/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.repair;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Future;

import com.google.common.base.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.dht.Bounds;
import org.apache.cassandra.dht.LocalPartitioner;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.repair.messages.*;
import org.apache.cassandra.service.ActiveRepairService;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;

/**
 * Handles all repair related message.
 *
 * @since 2.0
 */
public class RepairMessageVerbHandler implements IVerbHandler<RepairMessage>
{
    private static final Logger logger = LoggerFactory.getLogger(RepairMessageVerbHandler.class);
    public void doVerb(MessageIn<RepairMessage> message, int id)
    {
        // TODO add cancel/interrupt message
        RepairJobDesc desc = message.payload.desc;
        switch (message.payload.messageType)
        {
            case PREPARE_MESSAGE:
                PrepareMessage prepareMessage = (PrepareMessage) message.payload;
                List<ColumnFamilyStore> columnFamilyStores = new ArrayList<>(prepareMessage.cfIds.size());
                for (UUID cfId : prepareMessage.cfIds)
                {
                    Pair<String, String> kscf = Schema.instance.getCF(cfId);
                    ColumnFamilyStore columnFamilyStore = Keyspace.open(kscf.left).getColumnFamilyStore(kscf.right);
                    columnFamilyStores.add(columnFamilyStore);
                }
                ActiveRepairService.instance.registerParentRepairSession(prepareMessage.parentRepairSession,
                                                                         columnFamilyStores,
                                                                         prepareMessage.ranges);
                MessagingService.instance().sendReply(new MessageOut(MessagingService.Verb.INTERNAL_RESPONSE), id, message.from);
                break;

            case SNAPSHOT:
                ColumnFamilyStore cfs = Keyspace.open(desc.keyspace).getColumnFamilyStore(desc.columnFamily);
                final Range<Token> repairingRange = desc.range;
                cfs.snapshot(desc.sessionId.toString(), new Predicate<SSTableReader>()
                {
                    public boolean apply(SSTableReader sstable)
                    {
                        return sstable != null &&
                               !(sstable.partitioner instanceof LocalPartitioner) && // exclude SSTables from 2i
                               new Bounds<>(sstable.first.getToken(), sstable.last.getToken()).intersects(Collections.singleton(repairingRange));
                    }
                });

                logger.debug("Enqueuing response to snapshot request {} to {}", desc.sessionId, message.from);
                MessagingService.instance().sendReply(new MessageOut(MessagingService.Verb.INTERNAL_RESPONSE), id, message.from);
                break;

            case VALIDATION_REQUEST:
                ValidationRequest validationRequest = (ValidationRequest) message.payload;
                // trigger read-only compaction
                ColumnFamilyStore store = Keyspace.open(desc.keyspace).getColumnFamilyStore(desc.columnFamily);

                Validator validator = new Validator(desc, message.from, validationRequest.gcBefore);
                CompactionManager.instance.submitValidation(store, validator);
                break;

            case SYNC_REQUEST:
                // forwarded sync request
                SyncRequest request = (SyncRequest) message.payload;

                long repairedAt = ActiveRepairService.UNREPAIRED_SSTABLE;
                if (desc.parentSessionId != null && ActiveRepairService.instance.getParentRepairSession(desc.parentSessionId) != null)
                    repairedAt = ActiveRepairService.instance.getParentRepairSession(desc.parentSessionId).repairedAt;

                StreamingRepairTask task = new StreamingRepairTask(desc, request, repairedAt);
                task.run();
                break;

            case ANTICOMPACTION_REQUEST:
                logger.debug("Got anticompaction request");
                AnticompactionRequest anticompactionRequest = (AnticompactionRequest) message.payload;
                try
                {
                    List<Future<?>> futures = ActiveRepairService.instance.doAntiCompaction(anticompactionRequest.parentRepairSession);
                    FBUtilities.waitOnFutures(futures);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }

                break;

            default:
                ActiveRepairService.instance.handleMessage(message.from, message.payload);
                break;
        }
    }
}
