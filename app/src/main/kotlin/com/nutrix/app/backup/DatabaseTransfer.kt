package com.nutrix.app.backup

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase

/** Copies application tables inside transactions owned by the caller; Room metadata stays local. */
internal object DatabaseTransfer {
    fun tables(db: SupportSQLiteDatabase): List<String> = db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
            "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun replace(source: SupportSQLiteDatabase, target: SupportSQLiteDatabase) {
        val tables = tables(target)
        require(tables == tables(source)) { "Incompatible diary tables" }
        target.execSQL("PRAGMA defer_foreign_keys = ON")
        tables.forEach { target.execSQL("DELETE FROM ${quoted(it)}") }
        tables.forEach { table ->
            source.query("SELECT * FROM ${quoted(table)}").use { rows ->
                val columns = rows.columnNames.joinToString(",", transform = ::quoted)
                val placeholders = List(rows.columnCount) { "?" }.joinToString(",")
                target.compileStatement("INSERT INTO ${quoted(table)} ($columns) VALUES ($placeholders)").use { insert ->
                    while (rows.moveToNext()) {
                        insert.clearBindings()
                        for (column in 0 until rows.columnCount) {
                            val position = column + 1
                            when (rows.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> insert.bindNull(position)
                                Cursor.FIELD_TYPE_INTEGER -> insert.bindLong(position, rows.getLong(column))
                                Cursor.FIELD_TYPE_FLOAT -> insert.bindDouble(position, rows.getDouble(column))
                                Cursor.FIELD_TYPE_BLOB -> insert.bindBlob(position, rows.getBlob(column))
                                else -> insert.bindString(position, rows.getString(column))
                            }
                        }
                        insert.executeInsert()
                    }
                }
            }
        }
        target.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) { "Invalid diary relationships" } }
    }

    private fun quoted(name: String): String {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid database identifier" }
        return "\"$name\""
    }
}
