package org.jetbrains.exposed

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.flushCache
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.*
import org.jetbrains.exposed.sql.statements.BatchDataInconsistentException
import org.jetbrains.exposed.sql.statements.BatchInsertStatement
import org.jetbrains.exposed.sql.tests.DatabaseTestsBase
import org.jetbrains.exposed.sql.tests.TestDB
import org.jetbrains.exposed.sql.tests.currentDialectTest
import org.jetbrains.exposed.sql.tests.inProperCase
import org.jetbrains.exposed.sql.tests.shared.assertEqualCollections
import org.jetbrains.exposed.sql.tests.shared.assertEqualLists
import org.jetbrains.exposed.sql.tests.shared.assertEquals
import org.jetbrains.exposed.sql.tests.shared.expectException
import org.jetbrains.exposed.sql.vendors.H2Dialect
import org.jetbrains.exposed.sql.vendors.MysqlDialect
import org.jetbrains.exposed.sql.vendors.OracleDialect
import org.jetbrains.exposed.sql.vendors.SQLServerDialect
import org.jetbrains.exposed.sql.vendors.h2Mode
import org.junit.Test
import java.time.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultsTest : DatabaseTestsBase() {
    object TableWithDBDefault : IntIdTable() {
        var cIndex = 0
        val field = varchar("field", 100)
        val t1 = datetime("t1").defaultExpression(CurrentDateTime)
        val clientDefault = integer("clientDefault").clientDefault { cIndex++ }
    }

    class DBDefault(id: EntityID<Int>) : IntEntity(id) {
        var field by TableWithDBDefault.field
        var t1 by TableWithDBDefault.t1
        var clientDefault by TableWithDBDefault.clientDefault

        override fun equals(other: Any?): Boolean {
            return (other as? DBDefault)?.let { id == it.id && field == it.field && equalDateTime(t1, it.t1) } ?: false
        }

        override fun hashCode(): Int = id.value.hashCode()

        companion object : IntEntityClass<DBDefault>(TableWithDBDefault)
    }

    @Test
    fun testCanUseClientDefaultOnNullableColumn() {
        val defaultValue: Int? = null
        val table = object : IntIdTable() {
            val clientDefault = integer("clientDefault").nullable().clientDefault { defaultValue }
        }
        val returnedDefault = table.clientDefault.defaultValueFun?.invoke()

        assertTrue(table.clientDefault.columnType.nullable, "Expected clientDefault columnType to be nullable")
        assertNotNull(table.clientDefault.defaultValueFun, "Expected clientDefault column to have a default value fun, but was null")
        assertEquals(defaultValue, returnedDefault, "Expected clientDefault to return $defaultValue, but was $returnedDefault")
    }

    @Test
    fun testCanSetNullableColumnToUseClientDefault() {
        val defaultValue = 123
        val table = object : IntIdTable() {
            val clientDefault = integer("clientDefault").clientDefault { defaultValue }.nullable()
        }
        val returnedDefault = table.clientDefault.defaultValueFun?.invoke()

        assertTrue(table.clientDefault.columnType.nullable, "Expected clientDefault columnType to be nullable")
        assertNotNull(table.clientDefault.defaultValueFun, "Expected clientDefault column to have a default value fun, but was null")
        assertEquals(defaultValue, returnedDefault, "Expected clientDefault to return $defaultValue, but was $returnedDefault")
    }

    @Test
    fun testDefaultsWithExplicit01() {
        withTables(TableWithDBDefault) {
            val created = listOf(
                DBDefault.new { field = "1" },
                DBDefault.new {
                    field = "2"
                    t1 = LocalDateTime.now().minusDays(5)
                }
            )
            commit()
            created.forEach {
                DBDefault.removeFromCache(it)
            }

            val entities = DBDefault.all().toList()
            assertEqualCollections(created.map { it.id }, entities.map { it.id })
        }
    }

    @Test
    fun testDefaultsWithExplicit02() {
        withTables(TableWithDBDefault) {
            val created = listOf(
                DBDefault.new {
                    field = "2"
                    t1 = LocalDateTime.now().minusDays(5)
                },
                DBDefault.new { field = "1" }
            )

            flushCache()
            created.forEach {
                DBDefault.removeFromCache(it)
            }
            val entities = DBDefault.all().toList()
            assertEqualCollections(created, entities)
        }
    }

    @Test
    fun testDefaultsInvokedOnlyOncePerEntity() {
        withTables(TableWithDBDefault) {
            TableWithDBDefault.cIndex = 0
            val db1 = DBDefault.new { field = "1" }
            val db2 = DBDefault.new { field = "2" }
            flushCache()
            assertEquals(0, db1.clientDefault)
            assertEquals(1, db2.clientDefault)
            assertEquals(2, TableWithDBDefault.cIndex)
        }
    }

    @Test
    fun testDefaultsCanBeOverridden() {
        withTables(TableWithDBDefault) {
            TableWithDBDefault.cIndex = 0
            val db1 = DBDefault.new { field = "1" }
            val db2 = DBDefault.new { field = "2" }
            db1.clientDefault = 12345
            flushCache()
            assertEquals(12345, db1.clientDefault)
            assertEquals(1, db2.clientDefault)
            assertEquals(2, TableWithDBDefault.cIndex)

            flushCache()
            assertEquals(12345, db1.clientDefault)
        }
    }

    private val initBatch = listOf<(BatchInsertStatement) -> Unit>(
        {
            it[TableWithDBDefault.field] = "1"
        },
        {
            it[TableWithDBDefault.field] = "2"
            it[TableWithDBDefault.t1] = LocalDateTime.now()
        }
    )

    @Test
    fun testRawBatchInsertFails01() {
        withTables(TableWithDBDefault) {
            expectException<BatchDataInconsistentException> {
                BatchInsertStatement(TableWithDBDefault).run {
                    initBatch.forEach {
                        addBatch()
                        it(this)
                    }
                }
            }
        }
    }

    @Test
    fun testBatchInsertNotFails01() {
        withTables(TableWithDBDefault) {
            TableWithDBDefault.batchInsert(initBatch) { foo ->
                foo(this)
            }
        }
    }

    @Test
    fun testBatchInsertFails01() {
        withTables(TableWithDBDefault) {
            expectException<BatchDataInconsistentException> {
                TableWithDBDefault.batchInsert(listOf(1)) {
                    this[TableWithDBDefault.t1] = LocalDateTime.now()
                }
            }
        }
    }

    @Test
    fun testDefaults01() {
        val currentDT = CurrentDateTime
        val nowExpression = object : Expression<LocalDateTime>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
                +when (val dialect = currentDialectTest) {
                    is OracleDialect -> "SYSDATE"
                    is SQLServerDialect -> "GETDATE()"
                    is MysqlDialect -> if (dialect.isFractionDateTimeSupported()) "NOW(6)" else "NOW()"
                    else -> "NOW()"
                }
            }
        }
        val dtConstValue = LocalDate.of(2010, 1, 1)
        val dLiteral = dateLiteral(dtConstValue)
        val dtLiteral = dateTimeLiteral(dtConstValue.atStartOfDay())
        val tsConstValue = dtConstValue.atStartOfDay(ZoneOffset.UTC).plusSeconds(42).toInstant()
        val tsLiteral = timestampLiteral(tsConstValue)
        val durConstValue = Duration.between(Instant.EPOCH, tsConstValue)
        val durLiteral = durationLiteral(durConstValue)
        val tmConstValue = LocalTime.of(12, 0)
        val tLiteral = timeLiteral(tmConstValue)

        val TestTable = object : IntIdTable("t") {
            val s = varchar("s", 100).default("test")
            val sn = varchar("sn", 100).default("testNullable").nullable()
            val l = long("l").default(42)
            val c = char("c").default('X')
            val t1 = datetime("t1").defaultExpression(currentDT)
            val t2 = datetime("t2").defaultExpression(nowExpression)
            val t3 = datetime("t3").defaultExpression(dtLiteral)
            val t4 = date("t4").default(dtConstValue)
            val t5 = timestamp("t5").default(tsConstValue)
            val t6 = timestamp("t6").defaultExpression(tsLiteral)
            val t7 = duration("t7").default(durConstValue)
            val t8 = duration("t8").defaultExpression(durLiteral)
            val t9 = time("t9").default(tmConstValue)
            val t10 = time("t10").defaultExpression(tLiteral)
        }

        fun Expression<*>.itOrNull() = when {
            currentDialectTest.isAllowedAsColumnDefault(this) ->
                "DEFAULT ${currentDialectTest.dataTypeProvider.processForDefaultValue(this)} NOT NULL"
            else -> "NULL"
        }

        withTables(listOf(TestDB.SQLITE), TestTable) {
            val dtType = currentDialectTest.dataTypeProvider.dateTimeType()
            val longType = currentDialectTest.dataTypeProvider.longType()
            val timeType = currentDialectTest.dataTypeProvider.timeType()
            val varcharType = currentDialectTest.dataTypeProvider.varcharType(100)
            val q = db.identifierManager.quoteString
            val baseExpression = "CREATE TABLE " + addIfNotExistsIfSupported() +
                "${"t".inProperCase()} (" +
                "${"id".inProperCase()} ${currentDialectTest.dataTypeProvider.integerAutoincType()} PRIMARY KEY, " +
                "${"s".inProperCase()} $varcharType DEFAULT 'test' NOT NULL, " +
                "${"sn".inProperCase()} $varcharType DEFAULT 'testNullable' NULL, " +
                "${"l".inProperCase()} ${currentDialectTest.dataTypeProvider.longType()} DEFAULT 42 NOT NULL, " +
                "$q${"c".inProperCase()}$q CHAR DEFAULT 'X' NOT NULL, " +
                "${"t1".inProperCase()} $dtType ${currentDT.itOrNull()}, " +
                "${"t2".inProperCase()} $dtType ${nowExpression.itOrNull()}, " +
                "${"t3".inProperCase()} $dtType ${dtLiteral.itOrNull()}, " +
                "${"t4".inProperCase()} DATE ${dLiteral.itOrNull()}, " +
                "${"t5".inProperCase()} $dtType ${tsLiteral.itOrNull()}, " +
                "${"t6".inProperCase()} $dtType ${tsLiteral.itOrNull()}, " +
                "${"t7".inProperCase()} $longType ${durLiteral.itOrNull()}, " +
                "${"t8".inProperCase()} $longType ${durLiteral.itOrNull()}, " +
                "${"t9".inProperCase()} $timeType ${tLiteral.itOrNull()}, " +
                "${"t10".inProperCase()} $timeType ${tLiteral.itOrNull()}" +
                ")"

            val expected = if (currentDialectTest is OracleDialect || currentDialectTest.h2Mode == H2Dialect.H2CompatibilityMode.Oracle) {
                arrayListOf("CREATE SEQUENCE t_id_seq START WITH 1 MINVALUE 1 MAXVALUE 9223372036854775807", baseExpression)
            } else {
                arrayListOf(baseExpression)
            }

            assertEqualLists(expected, TestTable.ddl)

            val id1 = TestTable.insertAndGetId { }

            val row1 = TestTable.select { TestTable.id eq id1 }.single()
            assertEquals("test", row1[TestTable.s])
            assertEquals("testNullable", row1[TestTable.sn])
            assertEquals(42, row1[TestTable.l])
            assertEquals('X', row1[TestTable.c])
            assertEqualDateTime(dtConstValue.atStartOfDay(), row1[TestTable.t3])
            assertEqualDateTime(dtConstValue, row1[TestTable.t4])
            assertEqualDateTime(tsConstValue, row1[TestTable.t5])
            assertEqualDateTime(tsConstValue, row1[TestTable.t6])
            assertEquals(durConstValue, row1[TestTable.t7])
            assertEquals(durConstValue, row1[TestTable.t8])
            assertEquals(tmConstValue, row1[TestTable.t9])
            assertEquals(tmConstValue, row1[TestTable.t10])
        }
    }

    @Test
    fun testDefaultExpressions01() {
        fun abs(value: Int) = object : ExpressionWithColumnType<Int>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder { append("ABS($value)") }

            override val columnType: IColumnType = IntegerColumnType()
        }

        val foo = object : IntIdTable("foo") {
            val name = text("name")
            val defaultDateTime = datetime("defaultDateTime").defaultExpression(CurrentDateTime)
            val defaultDate = date("defaultDate").defaultExpression(CurrentDate)
            val defaultInt = integer("defaultInteger").defaultExpression(abs(-100))
        }

        withTables(foo) {
            val id = foo.insertAndGetId {
                it[foo.name] = "bar"
            }
            val result = foo.select { foo.id eq id }.single()

            assertEquals(today, result[foo.defaultDateTime].toLocalDate())
            assertEquals(today, result[foo.defaultDate])
            assertEquals(100, result[foo.defaultInt])
        }
    }

    @Test
    fun testDefaultExpressions02() {
        val foo = object : IntIdTable("foo") {
            val name = text("name")
            val defaultDateTime = datetime("defaultDateTime").defaultExpression(CurrentTimestamp())
        }

        val nonDefaultDate = LocalDate.of(2000, 1, 1).atStartOfDay()

        withTables(foo) {
            val id = foo.insertAndGetId {
                it[foo.name] = "bar"
                it[foo.defaultDateTime] = nonDefaultDate
            }

            val result = foo.select { foo.id eq id }.single()

            assertEquals("bar", result[foo.name])
            assertEqualDateTime(nonDefaultDate, result[foo.defaultDateTime])

            foo.update({ foo.id eq id }) {
                it[foo.name] = "baz"
            }

            val result2 = foo.select { foo.id eq id }.single()
            assertEquals("baz", result2[foo.name])
            assertEqualDateTime(nonDefaultDate, result2[foo.defaultDateTime])
        }
    }

    @Test
    fun testBetweenFunction() {
        val foo = object : IntIdTable("foo") {
            val dt = datetime("dateTime")
        }

        withTables(foo) {
            val dt2020 = LocalDateTime.of(2020, 1, 1, 1, 1)
            foo.insert { it[dt] = LocalDateTime.of(2019, 1, 1, 1, 1) }
            foo.insert { it[dt] = dt2020 }
            foo.insert { it[dt] = LocalDateTime.of(2021, 1, 1, 1, 1) }
            val count = foo.select { foo.dt.between(dt2020.minusWeeks(1), dt2020.plusWeeks(1)) }.count()
            assertEquals(1, count)
        }
    }

    @Test
    fun testConsistentSchemeWithFunctionAsDefaultExpression() {
        val foo = object : IntIdTable("foo") {
            val name = text("name")
            val defaultDateTime = datetime("defaultDateTime").defaultExpression(CurrentDateTime)
        }

        withDb {
            try {
                SchemaUtils.create(foo)

                val actual = SchemaUtils.statementsRequiredToActualizeScheme(foo)
                assertTrue(actual.isEmpty())
            } finally {
                SchemaUtils.drop(foo)
            }
        }
    }
}
