/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.query;

import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.util.Collections;

import org.neo4j.graphdb.DependencyResolver;
import org.neo4j.kernel.GraphDatabaseQueryService;
import org.neo4j.kernel.api.ExecutingQuery;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.QueryRegistryOperations;
import org.neo4j.kernel.api.Statement;
import org.neo4j.kernel.api.dbms.DbmsOperations;
import org.neo4j.kernel.api.security.AccessMode;
import org.neo4j.kernel.guard.Guard;
import org.neo4j.kernel.impl.api.KernelStatement;
import org.neo4j.kernel.impl.core.ThreadToStatementContextBridge;
import org.neo4j.kernel.impl.coreapi.InternalTransaction;
import org.neo4j.kernel.impl.coreapi.PropertyContainerLocker;
import org.neo4j.kernel.impl.coreapi.TopLevelTransaction;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class Neo4jTransactionalContextTest
{

    private GraphDatabaseQueryService databaseQueryService;
    private DependencyResolver dependencyResolver;
    private ThreadToStatementContextBridge statementContextBridge;
    private Guard guard;
    private KernelStatement statement;
    private PropertyContainerLocker propertyContainerLocker;
    private TopLevelTransaction transaction;

    @Before
    public void setUp()
    {
        setUpMocks();
    }

    @Test
    public void checkKernelStatementOnCheck() throws Exception
    {
        Neo4jTransactionalContext transactionalContext =
                new Neo4jTransactionalContext( databaseQueryService, transaction, statement, "", Collections.emptyMap(),
                        propertyContainerLocker );

        transactionalContext.check();

        verify( guard ).check( statement );
    }

    @Test
    public void neverStopsExecutingQueryDuringCommitAndRestartTx()
    {
        // Given
        KernelTransaction initialKTX = mock( KernelTransaction.class );
        KernelTransaction.Type transactionType = null;
        AccessMode transactionMode = null;
        QueryRegistryOperations initialQueryRegistry = mock( QueryRegistryOperations.class );
        ExecutingQuery executingQuery = mock( ExecutingQuery.class );
        PropertyContainerLocker locker = null;
        ThreadToStatementContextBridge txBridge = mock( ThreadToStatementContextBridge.class );
        Guard guard = mock( Guard.class );
        DbmsOperations.Factory dbmsOperationsFactory = null;

        KernelTransaction secondKTX = mock( KernelTransaction.class );
        InternalTransaction secondTransaction = mock( InternalTransaction.class );
        Statement secondStatement = mock( Statement.class );
        QueryRegistryOperations secondQueryRegistry = mock( QueryRegistryOperations.class );

        when( executingQuery.queryText() ).thenReturn( "X" );
        when( executingQuery.queryParameters() ).thenReturn( Collections.emptyMap() );
        when( statement.queryRegistration() ).thenReturn( initialQueryRegistry );
        when( databaseQueryService.beginTransaction( transactionType, transactionMode ) ).thenReturn( secondTransaction );
        when( txBridge.getKernelTransactionBoundToThisThread( true ) ).thenReturn( initialKTX, secondKTX );
        when( txBridge.get() ).thenReturn( secondStatement );
        when( secondStatement.queryRegistration() ).thenReturn( secondQueryRegistry );

        Neo4jTransactionalContext context = new Neo4jTransactionalContext(
                databaseQueryService, transaction, transactionType, transactionMode, statement, executingQuery,
                locker, txBridge, dbmsOperationsFactory, guard );

        // When
        context.commitAndRestartTx();

        // Then
        Object[] mocks =
                {txBridge, transaction, initialQueryRegistry, initialKTX, secondQueryRegistry, secondKTX};
        InOrder order = Mockito.inOrder( mocks );

        // (1) Unbind old
        order.verify( txBridge ).getKernelTransactionBoundToThisThread( true );
        order.verify( txBridge ).unbindTransactionFromCurrentThread();

        // (2) Register and unbind new
        order.verify( txBridge ).get();
        order.verify( secondQueryRegistry ).registerExecutingQuery( executingQuery );
        order.verify( txBridge ).getKernelTransactionBoundToThisThread( true );
        order.verify( txBridge ).unbindTransactionFromCurrentThread();

        // (3) Rebind, unregister, and close old
        order.verify( txBridge ).bindTransactionToCurrentThread( initialKTX );
        order.verify( initialQueryRegistry ).unregisterExecutingQuery( executingQuery );
        order.verify( transaction ).success();
        order.verify( transaction ).close();
        order.verify( txBridge ).unbindTransactionFromCurrentThread();

        // (4) Rebind new
        order.verify( txBridge ).bindTransactionToCurrentThread( secondKTX );
        verifyNoMoreInteractions( mocks );
    }

    private void setUpMocks()
    {
        databaseQueryService = mock( GraphDatabaseQueryService.class );
        dependencyResolver = mock( DependencyResolver.class );
        statementContextBridge = mock( ThreadToStatementContextBridge.class );
        guard = mock( Guard.class );
        statement = mock( KernelStatement.class );
        propertyContainerLocker = mock( PropertyContainerLocker.class );
        transaction = new TopLevelTransaction( mock( KernelTransaction.class ), () -> statement );

        when( databaseQueryService.getDependencyResolver() ).thenReturn( dependencyResolver );
        when( dependencyResolver.resolveDependency( ThreadToStatementContextBridge.class ) ).thenReturn( statementContextBridge );
        when( dependencyResolver.resolveDependency( Guard.class ) ).thenReturn( guard );
    }
}
