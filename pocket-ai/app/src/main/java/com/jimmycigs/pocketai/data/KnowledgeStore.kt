package com.jimmycigs.pocketai.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.jimmycigs.pocketai.ai.ChatMessage
import com.jimmycigs.pocketai.ai.Memory
import com.jimmycigs.pocketai.ai.Role
import org.json.JSONArray
import org.json.JSONObject

/** On-device SQLite storage for the knowledge base and the chat history. */
class KnowledgeStore(context: Context) : SQLiteOpenHelper(context, "pocketai.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE memories (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, " +
                "source TEXT NOT NULL, created_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT NOT NULL, " +
                "text TEXT NOT NULL, created_at INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun memories(): List<Memory> =
        readableDatabase.query("memories", null, null, null, null, null, "created_at DESC, id DESC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        Memory(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            text = c.getString(c.getColumnIndexOrThrow("text")),
                            source = c.getString(c.getColumnIndexOrThrow("source")),
                            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        ),
                    )
                }
            }
        }

    fun addMemory(text: String, source: String, createdAt: Long = System.currentTimeMillis()): Long =
        writableDatabase.insert(
            "memories",
            null,
            ContentValues().apply {
                put("text", text)
                put("source", source)
                put("created_at", createdAt)
            },
        )

    fun updateMemory(id: Long, text: String) {
        writableDatabase.update("memories", ContentValues().apply { put("text", text) }, "id = ?", arrayOf("$id"))
    }

    fun deleteMemory(id: Long) {
        writableDatabase.delete("memories", "id = ?", arrayOf("$id"))
    }

    fun deleteAllMemories() {
        writableDatabase.delete("memories", null, null)
    }

    fun messages(): List<ChatMessage> =
        readableDatabase.query("messages", null, null, null, null, null, "id ASC").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        ChatMessage(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            role = Role.valueOf(c.getString(c.getColumnIndexOrThrow("role"))),
                            text = c.getString(c.getColumnIndexOrThrow("text")),
                            createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                        ),
                    )
                }
            }
        }

    fun addMessage(role: Role, text: String): ChatMessage {
        val now = System.currentTimeMillis()
        val id = writableDatabase.insert(
            "messages",
            null,
            ContentValues().apply {
                put("role", role.name)
                put("text", text)
                put("created_at", now)
            },
        )
        return ChatMessage(id, role, text, now)
    }

    fun deleteAllMessages() {
        writableDatabase.delete("messages", null, null)
    }

    /** Backup format: {"memories": [{"text", "source", "createdAt"}]}. */
    fun exportJson(): String {
        val items = JSONArray()
        memories().forEach { memory ->
            items.put(
                JSONObject()
                    .put("text", memory.text)
                    .put("source", memory.source)
                    .put("createdAt", memory.createdAt),
            )
        }
        return JSONObject().put("version", 1).put("memories", items).toString(2)
    }

    /** Parses a backup made by [exportJson] into (text, source, createdAt) entries. */
    fun parseBackup(json: String): List<Triple<String, String, Long>> {
        val items = JSONObject(json).getJSONArray("memories")
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            Triple(
                item.getString("text"),
                item.optString("source", Memory.SOURCE_CHAT),
                item.optLong("createdAt", System.currentTimeMillis()),
            )
        }
    }
}
