/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.plugin.memory;

import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.ConnectorViewDefinition;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.testing.TestingNodeManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.spi.StandardErrorCode.ALREADY_EXISTS;
import static com.facebook.presto.spi.StandardErrorCode.NOT_FOUND;
import static com.facebook.presto.testing.TestingConnectorSession.SESSION;
import static io.airlift.testing.Assertions.assertEqualsIgnoreOrder;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test(singleThreaded = true)
public class TestMemoryMetadata
{
    private MemoryMetadata metadata;

    @BeforeMethod
    public void setUp()
    {
        metadata = new MemoryMetadata(new TestingNodeManager(), new MemoryConnectorId("test"));
    }

    @Test
    public void tableIsCreatedAfterCommits()
    {
        assertNoTables();

        SchemaTableName schemaTableName = new SchemaTableName("default", "temp_table");

        ConnectorOutputTableHandle table = metadata.beginCreateTable(
                SESSION,
                new ConnectorTableMetadata(schemaTableName, ImmutableList.of(), ImmutableMap.of()),
                Optional.empty());

        metadata.finishCreateTable(SESSION, table, ImmutableList.of());

        List<SchemaTableName> tables = metadata.listTables(SESSION, null);
        assertTrue(tables.size() == 1, "Expected only one table");
        assertTrue(tables.get(0).getTableName().equals("temp_table"), "Expected table with name 'temp_table'");
    }

    @Test
    public void tableAlreadyExists()
    {
        assertNoTables();

        SchemaTableName test1Table = new SchemaTableName("default", "test1");
        SchemaTableName test2Table = new SchemaTableName("default", "test2");
        metadata.createTable(SESSION, new ConnectorTableMetadata(test1Table, ImmutableList.of()), false);

        try {
            metadata.createTable(SESSION, new ConnectorTableMetadata(test1Table, ImmutableList.of()), false);
            fail("Should fail because table already exists");
        }
        catch (PrestoException ex) {
            assertEquals(ex.getErrorCode(), ALREADY_EXISTS.toErrorCode());
            assertEquals(ex.getMessage(), "Table [default.test1] already exists");
        }

        ConnectorTableHandle test1TableHandle = metadata.getTableHandle(SESSION, test1Table);
        metadata.createTable(SESSION, new ConnectorTableMetadata(test2Table, ImmutableList.of()), false);

        try {
            metadata.renameTable(SESSION, test1TableHandle, test2Table);
            fail("Should fail because table already exists");
        }
        catch (PrestoException ex) {
            assertEquals(ex.getErrorCode(), ALREADY_EXISTS.toErrorCode());
            assertEquals(ex.getMessage(), "Table [default.test2] already exists");
        }
    }

    @Test
    public void testActiveTableIds()
    {
        assertNoTables();

        SchemaTableName firstTableName = new SchemaTableName("default", "first_table");
        metadata.createTable(SESSION, new ConnectorTableMetadata(firstTableName, ImmutableList.of(), ImmutableMap.of()), false);

        MemoryTableHandle firstTableHandle = (MemoryTableHandle) metadata.getTableHandle(SESSION, firstTableName);
        Long firstTableId = firstTableHandle.getTableId();

        assertTrue(metadata.beginInsert(SESSION, firstTableHandle).getActiveTableIds().contains(firstTableId));

        SchemaTableName secondTableName = new SchemaTableName("default", "second_table");
        metadata.createTable(SESSION, new ConnectorTableMetadata(secondTableName, ImmutableList.of(), ImmutableMap.of()), false);

        MemoryTableHandle secondTableHandle = (MemoryTableHandle) metadata.getTableHandle(SESSION, secondTableName);
        Long secondTableId = secondTableHandle.getTableId();

        assertNotEquals(firstTableId, secondTableId);
        assertTrue(metadata.beginInsert(SESSION, secondTableHandle).getActiveTableIds().contains(firstTableId));
        assertTrue(metadata.beginInsert(SESSION, secondTableHandle).getActiveTableIds().contains(secondTableId));
    }

    @Test
    public void testReadTableBeforeCreationCompleted()
    {
        assertNoTables();

        SchemaTableName tableName = new SchemaTableName("default", "temp_table");

        ConnectorOutputTableHandle table = metadata.beginCreateTable(
                SESSION,
                new ConnectorTableMetadata(tableName, ImmutableList.of(), ImmutableMap.of()),
                Optional.empty());

        List<SchemaTableName> tableNames = metadata.listTables(SESSION, null);
        assertTrue(tableNames.size() == 1, "Expected exactly one table");

        ConnectorTableHandle tableHandle = metadata.getTableHandle(SESSION, tableName);
        List<ConnectorTableLayoutResult> tableLayouts = metadata.getTableLayouts(SESSION, tableHandle, Constraint.alwaysTrue(), Optional.empty());
        assertTrue(tableLayouts.size() == 1, "Expected exactly one layout.");
        ConnectorTableLayout tableLayout = tableLayouts.get(0).getTableLayout();
        ConnectorTableLayoutHandle tableLayoutHandle = tableLayout.getHandle();
        assertTrue(tableLayoutHandle instanceof MemoryTableLayoutHandle);
        assertTrue(((MemoryTableLayoutHandle) tableLayoutHandle).getDataFragments().isEmpty(), "Data fragments should be empty");

        metadata.finishCreateTable(SESSION, table, ImmutableList.of());
    }

    @Test
    public void testCreateSchema()
    {
        assertEquals(metadata.listSchemaNames(SESSION), ImmutableList.of("default"));
        metadata.createSchema(SESSION, "test", ImmutableMap.of());
        assertEquals(metadata.listSchemaNames(SESSION), ImmutableList.of("default", "test"));
        assertEquals(metadata.listTables(SESSION, "test"), ImmutableList.of());

        SchemaTableName tableName = new SchemaTableName("test", "first_table");
        metadata.createTable(
                SESSION,
                new ConnectorTableMetadata(
                        tableName,
                        ImmutableList.of(),
                        ImmutableMap.of()),
                false);

        assertEquals(metadata.listTables(SESSION, null), ImmutableList.of(tableName));
        assertEquals(metadata.listTables(SESSION, "test"), ImmutableList.of(tableName));
        assertEquals(metadata.listTables(SESSION, "default"), ImmutableList.of());
    }

    @Test(expectedExceptions = PrestoException.class, expectedExceptionsMessageRegExp = "View already exists: test\\.test_view")
    public void testCreateViewWithoutReplace()
    {
        SchemaTableName test = new SchemaTableName("test", "test_view");
        metadata.createSchema(SESSION, "test", ImmutableMap.of());
        try {
            metadata.createView(SESSION, test, "test", false);
        }
        catch (Exception e) {
            fail("should have succeeded");
        }
        metadata.createView(SESSION, test, "test", false);
    }

    @Test
    public void testCreateViewWithReplace()
    {
        SchemaTableName test = new SchemaTableName("test", "test_view");

        metadata.createSchema(SESSION, "test", ImmutableMap.of());
        metadata.createView(SESSION, test, "aaa", true);
        metadata.createView(SESSION, test, "bbb", true);

        assertEquals(metadata.getViews(SESSION, test.toSchemaTablePrefix()).get(test).getViewData(), "bbb");
    }

    @Test
    public void testViews()
    {
        SchemaTableName test1 = new SchemaTableName("test", "test_view1");
        SchemaTableName test2 = new SchemaTableName("test", "test_view2");

        // create schema
        metadata.createSchema(SESSION, "test", ImmutableMap.of());

        // create views
        metadata.createView(SESSION, test1, "test1", false);
        metadata.createView(SESSION, test2, "test2", false);

        // verify listing
        List<SchemaTableName> list = metadata.listViews(SESSION, "test");
        assertEqualsIgnoreOrder(list, ImmutableList.of(test1, test2));

        // verify getting data
        Map<SchemaTableName, ConnectorViewDefinition> views = metadata.getViews(SESSION, new SchemaTablePrefix("test"));
        assertEquals(views.keySet(), ImmutableSet.of(test1, test2));
        assertEquals(views.get(test1).getViewData(), "test1");
        assertEquals(views.get(test2).getViewData(), "test2");

        // all schemas
        Map<SchemaTableName, ConnectorViewDefinition> allViews = metadata.getViews(SESSION, new SchemaTablePrefix());
        assertEquals(allViews.keySet(), ImmutableSet.of(test1, test2));

        // exact match on one schema and table
        Map<SchemaTableName, ConnectorViewDefinition> exactMatchView = metadata.getViews(SESSION, new SchemaTablePrefix("test", "test_view1"));
        assertEquals(exactMatchView.keySet(), ImmutableSet.of(test1));

        // non-existent table
        Map<SchemaTableName, ConnectorViewDefinition> nonexistentTableView = metadata.getViews(SESSION, new SchemaTablePrefix("test", "nonexistenttable"));
        assertTrue(nonexistentTableView.isEmpty());

        // non-existent schema
        Map<SchemaTableName, ConnectorViewDefinition> nonexistentSchemaView = metadata.getViews(SESSION, new SchemaTablePrefix("nonexistentschema"));
        assertTrue(nonexistentSchemaView.isEmpty());

        // drop first view
        metadata.dropView(SESSION, test1);

        views = metadata.getViews(SESSION, new SchemaTablePrefix("test"));
        assertEquals(views.keySet(), ImmutableSet.of(test2));

        // drop second view
        metadata.dropView(SESSION, test2);

        views = metadata.getViews(SESSION, new SchemaTablePrefix("test"));
        assertTrue(views.isEmpty());

        // verify listing everything
        views = metadata.getViews(SESSION, new SchemaTablePrefix());
        assertTrue(views.isEmpty());
    }

    @Test
    public void testCreateTableAndViewInNotExistSchema()
    {
        assertEquals(metadata.listSchemaNames(SESSION), ImmutableList.of("default"));

        SchemaTableName table1 = new SchemaTableName("test1", "test_schema_table1");
        try {
            metadata.beginCreateTable(SESSION, new ConnectorTableMetadata(table1, ImmutableList.of(), ImmutableMap.of()), Optional.empty());
            fail("Should fail because schema does not exist");
        }
        catch (PrestoException ex) {
            assertEquals(ex.getErrorCode(), NOT_FOUND.toErrorCode());
            assertEquals(ex.getMessage(), "Schema test1 not found");
        }
        assertEquals(metadata.getTableHandle(SESSION, table1), null);

        SchemaTableName view2 = new SchemaTableName("test2", "test_schema_view2");
        try {
            metadata.createView(SESSION, view2, "aaa", false);
            fail("Should fail because schema does not exist");
        }
        catch (PrestoException ex) {
            assertEquals(ex.getErrorCode(), NOT_FOUND.toErrorCode());
            assertEquals(ex.getMessage(), "Schema test2 not found");
        }
        assertEquals(metadata.getTableHandle(SESSION, view2), null);

        SchemaTableName view3 = new SchemaTableName("test3", "test_schema_view3");
        try {
            metadata.createView(SESSION, view3, "bbb", true);
            fail("Should fail because schema does not exist");
        }
        catch (PrestoException ex) {
            assertEquals(ex.getErrorCode(), NOT_FOUND.toErrorCode());
            assertEquals(ex.getMessage(), "Schema test3 not found");
        }
        assertEquals(metadata.getTableHandle(SESSION, view3), null);

        assertEquals(metadata.listSchemaNames(SESSION), ImmutableList.of("default"));
    }

    private void assertNoTables()
    {
        assertEquals(metadata.listTables(SESSION, null), ImmutableList.of(), "No table was expected");
    }
}
