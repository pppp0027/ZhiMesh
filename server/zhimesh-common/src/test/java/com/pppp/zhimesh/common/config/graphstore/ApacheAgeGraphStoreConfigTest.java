package com.pppp.zhimesh.common.config.graphstore;

import org.apache.age.jdbc.base.Agtype;
import org.junit.jupiter.api.Test;
import org.postgresql.jdbc.PgConnection;

import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ApacheAgeGraphStoreConfigTest {

    @Test
    void prepareRunsAgeSessionSetupExactlyOncePerPhysicalConnection() throws Exception {
        Connection raw = mock(Connection.class);
        PgConnection pgConnection = mock(PgConnection.class);
        Statement statement = mock(Statement.class);
        when(raw.unwrap(PgConnection.class)).thenReturn(pgConnection);
        when(pgConnection.createStatement()).thenReturn(statement);

        Connection prepared = ApacheAgeGraphStoreConfig.AgeGraphDataSource.prepare(raw);

        assertSame(pgConnection, prepared);
        verify(pgConnection, times(1)).addDataType("agtype", Agtype.class);
        verify(statement, times(1)).execute("LOAD 'age'");
        verify(statement, times(1)).execute("SET search_path = ag_catalog, \"$user\", public;");
        verify(statement).close();
        verifyNoMoreInteractions(statement);
    }
}
