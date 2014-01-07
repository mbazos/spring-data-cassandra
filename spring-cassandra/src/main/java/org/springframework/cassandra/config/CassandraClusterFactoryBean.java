/*
 * Copyright 2011-2013 the original author or authors.
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
package org.springframework.cassandra.config;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.cassandra.core.CassandraTemplate;
import org.springframework.cassandra.core.cql.generator.CreateKeyspaceCqlGenerator;
import org.springframework.cassandra.core.cql.generator.DropKeyspaceCqlGenerator;
import org.springframework.cassandra.core.keyspace.CreateKeyspaceSpecification;
import org.springframework.cassandra.core.keyspace.DropKeyspaceSpecification;
import org.springframework.cassandra.core.keyspace.KeyspaceNameSpecification;
import org.springframework.cassandra.support.CassandraExceptionTranslator;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.support.PersistenceExceptionTranslator;
import org.springframework.util.StringUtils;

import com.datastax.driver.core.AuthProvider;
import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.HostDistance;
import com.datastax.driver.core.PoolingOptions;
import com.datastax.driver.core.ProtocolOptions.Compression;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import com.datastax.driver.core.policies.LoadBalancingPolicy;
import com.datastax.driver.core.policies.ReconnectionPolicy;
import com.datastax.driver.core.policies.RetryPolicy;

/**
 * Convenient factory for configuring a Cassandra Cluster.
 * 
 * @author Alex Shvid
 * @author Matthew T. Adams
 */
public class CassandraClusterFactoryBean implements FactoryBean<Cluster>, InitializingBean, DisposableBean,
		PersistenceExceptionTranslator {

	public static final String DEFAULT_CONTACT_POINTS = "localhost";
	public static final boolean DEFAULT_METRICS_ENABLED = true;
	public static final int DEFAULT_PORT = 9042;

	protected static final Logger log = LoggerFactory.getLogger(CassandraClusterFactoryBean.class);

	private Cluster cluster;

	/**
	 * Comma-delimited string of servers.
	 */
	private String contactPoints = DEFAULT_CONTACT_POINTS;
	private int port = CassandraClusterFactoryBean.DEFAULT_PORT;
	private CompressionType compressionType;
	private PoolingOptionsConfig localPoolingOptions;
	private PoolingOptionsConfig remotePoolingOptions;
	private SocketOptionsConfig socketOptions;
	private AuthProvider authProvider;
	private LoadBalancingPolicy loadBalancingPolicy;
	private ReconnectionPolicy reconnectionPolicy;
	private RetryPolicy retryPolicy;
	private boolean metricsEnabled = DEFAULT_METRICS_ENABLED;
	private List<CreateKeyspaceSpecification> keyspaceCreations = new ArrayList<CreateKeyspaceSpecification>();
	private List<DropKeyspaceSpecification> keyspaceDrops = new ArrayList<DropKeyspaceSpecification>();
	private List<String> startupScripts = new ArrayList<String>();
	private List<String> shutdownScripts = new ArrayList<String>();

	private final PersistenceExceptionTranslator exceptionTranslator = new CassandraExceptionTranslator();

	@Override
	public Cluster getObject() throws Exception {
		return cluster;
	}

	@Override
	public Class<? extends Cluster> getObjectType() {
		return Cluster.class;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	@Override
	public DataAccessException translateExceptionIfPossible(RuntimeException ex) {
		return exceptionTranslator.translateExceptionIfPossible(ex);
	}

	@Override
	public void afterPropertiesSet() throws Exception {

		if (!StringUtils.hasText(contactPoints)) {
			throw new IllegalArgumentException("at least one server is required");
		}

		Cluster.Builder builder = Cluster.builder();

		builder.addContactPoints(StringUtils.commaDelimitedListToStringArray(contactPoints)).withPort(port);

		if (compressionType != null) {
			builder.withCompression(convertCompressionType(compressionType));
		}

		if (localPoolingOptions != null) {
			builder.withPoolingOptions(configPoolingOptions(HostDistance.LOCAL, localPoolingOptions));
		}

		if (remotePoolingOptions != null) {
			builder.withPoolingOptions(configPoolingOptions(HostDistance.REMOTE, remotePoolingOptions));
		}

		if (socketOptions != null) {
			builder.withSocketOptions(configSocketOptions(socketOptions));
		}

		if (authProvider != null) {
			builder.withAuthProvider(authProvider);
		}

		if (loadBalancingPolicy != null) {
			builder.withLoadBalancingPolicy(loadBalancingPolicy);
		}

		if (reconnectionPolicy != null) {
			builder.withReconnectionPolicy(reconnectionPolicy);
		}

		if (retryPolicy != null) {
			builder.withRetryPolicy(retryPolicy);
		}

		if (!metricsEnabled) {
			builder.withoutMetrics();
		}

		cluster = builder.build();
		executeSpecsAndScripts(keyspaceCreations, startupScripts);
	}

	protected void executeSpecsAndScripts(@SuppressWarnings("rawtypes") List specs, List<String> scripts) {

		Session system = null;
		CassandraTemplate template = null;

		try {
			if (specs != null) {
				system = specs.size() == 0 ? null : cluster.connect();
				template = system == null ? null : new CassandraTemplate(system);

				Iterator<?> i = specs.iterator();
				while (i.hasNext()) {
					KeyspaceNameSpecification<?> spec = (KeyspaceNameSpecification<?>) i.next();
					String cql = (spec instanceof CreateKeyspaceSpecification) ? new CreateKeyspaceCqlGenerator(
							(CreateKeyspaceSpecification) spec).toCql() : new DropKeyspaceCqlGenerator(
							(DropKeyspaceSpecification) spec).toCql();

					if (log.isInfoEnabled()) {
						log.info("executing CQL [{}]", cql);
					}

					template.execute(cql);
				}
			}

			if (scripts != null) {

				if (system == null) {
					system = scripts.size() == 0 ? null : cluster.connect();
				}

				if (template == null) {
					template = system == null ? null : new CassandraTemplate(system);
				}

				for (String script : scripts) {

					if (log.isInfoEnabled()) {
						log.info("executing raw CQL [{}]", script);
					}

					template.execute(script);
				}
			}
		} finally {

			if (system != null) {
				system.shutdown();
			}
		}
	}

	@Override
	public void destroy() throws Exception {

		executeSpecsAndScripts(keyspaceDrops, shutdownScripts);
		cluster.shutdown();
	}

	/**
	 * Sets a comma-delimited string of the contact points (hosts) to connect to.
	 */
	public void setContactPoints(String contactPoints) {
		this.contactPoints = contactPoints;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void setCompressionType(CompressionType compressionType) {
		this.compressionType = compressionType;
	}

	public void setLocalPoolingOptions(PoolingOptionsConfig localPoolingOptions) {
		this.localPoolingOptions = localPoolingOptions;
	}

	public void setRemotePoolingOptions(PoolingOptionsConfig remotePoolingOptions) {
		this.remotePoolingOptions = remotePoolingOptions;
	}

	public void setSocketOptions(SocketOptionsConfig socketOptions) {
		this.socketOptions = socketOptions;
	}

	public void setAuthProvider(AuthProvider authProvider) {
		this.authProvider = authProvider;
	}

	public void setLoadBalancingPolicy(LoadBalancingPolicy loadBalancingPolicy) {
		this.loadBalancingPolicy = loadBalancingPolicy;
	}

	public void setReconnectionPolicy(ReconnectionPolicy reconnectionPolicy) {
		this.reconnectionPolicy = reconnectionPolicy;
	}

	public void setRetryPolicy(RetryPolicy retryPolicy) {
		this.retryPolicy = retryPolicy;
	}

	public void setMetricsEnabled(boolean metricsEnabled) {
		this.metricsEnabled = metricsEnabled;
	}

	public void setKeyspaceCreations(List<CreateKeyspaceSpecification> specifications) {
		this.keyspaceCreations = specifications;
	}

	public List<CreateKeyspaceSpecification> getKeyspaceCreations() {
		return keyspaceCreations;
	}

	public void setKeyspaceDrops(List<DropKeyspaceSpecification> specifications) {
		this.keyspaceDrops = specifications;
	}

	public List<DropKeyspaceSpecification> getKeyspaceDrops() {
		return keyspaceDrops;
	}

	public void setStartupScripts(List<String> scripts) {
		this.startupScripts = scripts;
	}

	public void setShutdownScripts(List<String> scripts) {
		this.shutdownScripts = scripts;
	}

	private static Compression convertCompressionType(CompressionType type) {
		switch (type) {
		case NONE:
			return Compression.NONE;
		case SNAPPY:
			return Compression.SNAPPY;
		}
		throw new IllegalArgumentException("unknown compression type " + type);
	}

	private static PoolingOptions configPoolingOptions(HostDistance hostDistance, PoolingOptionsConfig config) {
		PoolingOptions poolingOptions = new PoolingOptions();

		if (config.getMinSimultaneousRequests() != null) {
			poolingOptions
					.setMinSimultaneousRequestsPerConnectionThreshold(hostDistance, config.getMinSimultaneousRequests());
		}
		if (config.getMaxSimultaneousRequests() != null) {
			poolingOptions
					.setMaxSimultaneousRequestsPerConnectionThreshold(hostDistance, config.getMaxSimultaneousRequests());
		}
		if (config.getCoreConnections() != null) {
			poolingOptions.setCoreConnectionsPerHost(hostDistance, config.getCoreConnections());
		}
		if (config.getMaxConnections() != null) {
			poolingOptions.setMaxConnectionsPerHost(hostDistance, config.getMaxConnections());
		}

		return poolingOptions;
	}

	private static SocketOptions configSocketOptions(SocketOptionsConfig config) {
		SocketOptions socketOptions = new SocketOptions();

		if (config.getConnectTimeoutMls() != null) {
			socketOptions.setConnectTimeoutMillis(config.getConnectTimeoutMls());
		}
		if (config.getKeepAlive() != null) {
			socketOptions.setKeepAlive(config.getKeepAlive());
		}
		if (config.getReuseAddress() != null) {
			socketOptions.setReuseAddress(config.getReuseAddress());
		}
		if (config.getSoLinger() != null) {
			socketOptions.setSoLinger(config.getSoLinger());
		}
		if (config.getTcpNoDelay() != null) {
			socketOptions.setTcpNoDelay(config.getTcpNoDelay());
		}
		if (config.getReceiveBufferSize() != null) {
			socketOptions.setReceiveBufferSize(config.getReceiveBufferSize());
		}
		if (config.getSendBufferSize() != null) {
			socketOptions.setSendBufferSize(config.getSendBufferSize());
		}

		return socketOptions;
	}
}