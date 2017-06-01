/*
 * Copyright [2017] Wikimedia Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.o19s.es.ltr.action;

import com.o19s.es.ltr.action.ClearCachesAction.ClearCachesNodesRequest;
import com.o19s.es.ltr.action.FeatureStoreAction.FeatureStoreRequest;
import com.o19s.es.ltr.action.FeatureStoreAction.FeatureStoreResponse;
import com.o19s.es.ltr.feature.store.StorableElement;
import com.o19s.es.ltr.feature.store.StoredFeature;
import com.o19s.es.ltr.feature.store.StoredFeatureSet;
import com.o19s.es.ltr.feature.store.StoredLtrModel;
import com.o19s.es.ltr.feature.store.index.IndexFeatureStore;
import com.o19s.es.ltr.ranker.parser.LtrRankerParserFactory;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.index.IndexAction;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.action.support.WriteRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Optional;

public class TransportFeatureStoreAction extends HandledTransportAction<FeatureStoreRequest, FeatureStoreResponse> {
    private final LtrRankerParserFactory factory;
    private final ClusterService clusterService;
    private final TransportClearCachesAction clearCachesAction;
    private final Client client;

    @Inject
    public TransportFeatureStoreAction(Settings settings, ThreadPool threadPool, TransportService transportService,
                                       ActionFilters actionFilters, IndexNameExpressionResolver indexNameExpressionResolver,
                                       ClusterService clusterService, Client client,
                                       LtrRankerParserFactory factory,
                                       TransportClearCachesAction clearCachesAction) {
        super(settings, FeatureStoreAction.NAME, false, threadPool, transportService, actionFilters,
                indexNameExpressionResolver, FeatureStoreRequest::new);
        this.factory = factory;
        this.clusterService = clusterService;
        this.clearCachesAction = clearCachesAction;
        this.client = client;
    }

    @Override
    protected void doExecute(FeatureStoreRequest request, ActionListener<FeatureStoreResponse> listener) {
        throw new UnsupportedOperationException("attempt to execute a TransportFeatureStoreAction without a task");
    }

    protected void doExecute(Task task, FeatureStoreRequest request, ActionListener<FeatureStoreResponse> listener) {
        if (!clusterService.state().routingTable().hasIndex(request.getStore())) {
            // To prevent index auto creation
            throw new IllegalArgumentException("Store [" + request.getStore() + "] does not exist, please create it first.");
        }
        // some extra validation steps that require the parser factory
        validate(request);
        Optional<ClearCachesNodesRequest> clearCachesNodesRequest = buildClearCache(request);
        try {
            IndexRequest indexRequest = buildIndexRequest(task, request);
            client.execute(IndexAction.INSTANCE, indexRequest, ActionListener.wrap(
                    (r) -> {
                        // Run and forget, log only if something bad happens
                        // but don't wait for the action to be done nor set the parent task.
                        clearCachesNodesRequest.ifPresent((req) -> clearCachesAction.execute(req, ActionListener.wrap(
                                (r2) -> {},
                                (e) -> logger.error("Failed to clear cache", e))));
                        listener.onResponse(new FeatureStoreResponse(r));
                    },
                    listener::onFailure));
        } catch(IOException ioe) {
            listener.onFailure(ioe);
        }
    }

    private Optional<ClearCachesNodesRequest> buildClearCache(FeatureStoreRequest request) {
        if (request.getAction() == FeatureStoreRequest.Action.UPDATE) {
             ClearCachesAction.ClearCachesNodesRequest clearCachesNodesRequest = new ClearCachesAction.ClearCachesNodesRequest();
             switch (request.getStorableElement().type()) {
             case StoredFeature.TYPE:
                 clearCachesNodesRequest.clearFeature(request.getStore(), request.getStorableElement().name());
                 return Optional.of(clearCachesNodesRequest);
             case StoredFeatureSet.TYPE:
                 clearCachesNodesRequest.clearFeatureSet(request.getStore(), request.getStorableElement().name());
                 return Optional.of(clearCachesNodesRequest);
             }
        }
        return Optional.empty();
    }

    private IndexRequest buildIndexRequest(Task parentTask, FeatureStoreRequest request) throws IOException {
        StorableElement elt = request.getStorableElement();

        IndexRequest indexRequest = client.prepareIndex(request.getStore(), IndexFeatureStore.ES_TYPE, elt.id())
                .setCreate(request.getAction() == FeatureStoreRequest.Action.CREATE)
                .setRouting(request.getRouting())
                .setSource(IndexFeatureStore.toSource(elt))
                .setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
                .request();
        indexRequest.setParentTask(clusterService.localNode().getId(), parentTask.getId());
        return indexRequest;
    }

    private void validate(FeatureStoreRequest request) {
        if (request.getStorableElement() instanceof StoredLtrModel) {
            StoredLtrModel model = (StoredLtrModel) request.getStorableElement();
            try {
                model.compile(factory);
            } catch (Exception e) {
                throw new IllegalArgumentException("Error while parsing model [" +  model.name() + "]" +
                        " with type [" + model.rankingModelType() + "]", e);
            }
        }
    }
}
