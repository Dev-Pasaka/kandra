package io.kandra.kodein

import io.kandra.ktor.KandraConfig
import io.kandra.ktor.ReplicationStrategy
import io.kandra.core.KandraAuth
import io.kandra.test.KandraTestBackend

/**
 * Points an `install(Kandra) { ... }` block at the active test backend (Docker container by default,
 * the Scylla Cloud cluster with `KANDRA_TEST_BACKEND=cloud`; see [KandraTestBackend]): contact points, DC,
 * credentials, TLS truststore, a 30s request timeout (the plugin's 5s default is tight for DDL against a
 * freshly started container or over the WAN), and on the cloud a tablets-compatible
 * NetworkTopologyStrategy for auto-created keyspaces. Call it first; later lines may override.
 */
internal fun KandraConfig.useTestBackend(rf: Int = 1) {
    contactPoints = KandraTestBackend.contactPointsCsv
    localDatacenter = KandraTestBackend.localDatacenter
    auth { provider = KandraAuth.static(KandraTestBackend.username, KandraTestBackend.password) }
    pool { requestTimeoutMillis = 30_000 }
    KandraTestBackend.truststore?.let { ts ->
        ssl {
            enabled = true
            hostnameVerification = false // cloud node certs are not issued for the contact-point host names
            trustStorePath = ts.path
            trustStorePassword = ts.password
            trustStoreType = ts.type
        }
    }
    if (KandraTestBackend.isCloud) {
        replicationStrategy = ReplicationStrategy.NetworkTopologyStrategy(
            mapOf(KandraTestBackend.localDatacenter to KandraTestBackend.effectiveRf(rf))
        )
    }
}
