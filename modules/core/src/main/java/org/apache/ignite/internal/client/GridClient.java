/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.client;

import java.util.*;

/**
 * Ignite Java client API.
 * <p>
 * Contains functionality to get projections for accessing remote
 * data and compute functionality, as well as provide listeners on topology changes.
 * <p>
 * You can obtain an instance of {@code GridClient} through
 * {@link GridClientFactory#start(GridClientConfiguration)}. Note that you
 * can have multiple instances of {@code GridClient} running in the same VM. For
 * information on how to start or stop Grid please refer to {@link GridClientFactory} class.
 * <p>
 * Use following methods to get access to remote cache functionality:
 * <ul>
 * <li>{@link #data()}</li>
 * <li>{@link #data(String)}</li>
 * </ul>
 * Use following methods to get access to remote compute functionality:
 * <ul>
 * <li>{@link #compute()}</li>
 * </ul>
 * <h1 class="header">Affinity Awareness</h1>
 * One of the unique properties of the Ignite remote clients is that they are
 * affinity aware. In other words, both compute and data APIs will optionally
 * contact exactly the node where the data is cached based on some affinity key.
 * This allows for collocation of computations and data and avoids extra network
 * hops that would be necessary if non-affinity nodes were contacted.
 * <p>
 * If client can't access some of grid nodes directly (for example due to security restrictions)
 * either dedicated Router component could be used or some of Grid nodes could act as routers.
 * See {@link GridClientConfiguration#getRouters()} for more details.
 * @see GridClientCompute
 * @see GridClientData
 */
public interface GridClient extends AutoCloseable {
    /**
     * Gets a unique client identifier. This identifier is generated by factory on client creation
     * and used in identification and authentication procedure on server node.
     *
     * @return Generated client id.
     */
    public UUID id();

    /**
     * Gets a data projection for a default grid cache with {@code null} name.
     *
     * @return Data projection for grid cache with {@code null} name.
     * @throws GridClientException If client was closed.
     */
    public GridClientData data() throws GridClientException;

    /**
     * Gets a data projection for grid cache with name <tt>cacheName</tt>. If
     * no data configuration with given name was provided at client startup, an
     * exception will be thrown.
     *
     * @param cacheName Grid cache name for which data projection should be obtained.
     * @return Data projection for grid cache with name <tt>cacheName</tt>.
     * @throws GridClientException If client was closed or no configuration with given name was provided.
     */
    public GridClientData data(String cacheName) throws GridClientException;

    /**
     * Gets a default compute projection. Default compute projection will include all nodes
     * in remote grid. Selection of node that will be connected to perform operations will be
     * done according to {@link org.apache.ignite.internal.client.balancer.GridClientLoadBalancer} provided in client configuration or
     * according to affinity if projection call involves affinity key.
     * <p>
     * More restricted projection configurations may be created with {@link GridClientCompute} methods.
     *
     * @return Default compute projection.
     *
     * @see GridClientCompute
     */
    public GridClientCompute compute();

    /**
     * Adds topology listener. Remote grid topology is refreshed every
     * {@link GridClientConfiguration#getTopologyRefreshFrequency()} milliseconds. If any node was added or removed,
     * a listener will be notified.
     *
     * @param lsnr Listener to add.
     */
    public void addTopologyListener(GridClientTopologyListener lsnr);

    /**
     * Removes previously added topology listener.
     *
     * @param lsnr Listener to remove.
     */
    public void removeTopologyListener(GridClientTopologyListener lsnr);

    /**
     * Gets an unmodifiable snapshot of topology listeners list.
     *
     * @return List of topology listeners.
     */
    public Collection<GridClientTopologyListener> topologyListeners();

    /**
     * Indicates whether client is connected to remote Grid.
     * In other words it allow to determine if client is able to communicate
     * with Grid right now. If it can't all methods on Compute and Data projections
     * throw {@link GridClientDisconnectedException}.
     * <p>
     * Connection status is updated in background together with topology update.
     * See {@link GridClientConfiguration#getTopologyRefreshFrequency()} for more
     * details on how background topology update works.
     * <p>
     * Note that due to asynchronous nature of topology update and connectivity detection
     * this method gives no guarantees for subsequent calls for projections methods.
     * It can be used only fo diagnostic and monitoring purposes.
     *
     * @return Whether client is connected to remote Grid.
     */
    public boolean connected();

    /**
     * Closes client instance. This method is identical to
     * {@link GridClientFactory#stop(UUID) GridClientFactory.stop(clientId)}.
     * <p>
     * The method is invoked automatically on objects managed by the
     * {@code try-with-resources} statement.
     */
    @Override public void close();
}
