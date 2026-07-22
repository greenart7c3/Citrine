package com.greenart7c3.citrine.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A NIP-29 group managed by this relay. Only groups created here (via kind 9007) get a
 * row; h-tagged events referencing unknown group ids are stored unmanaged. [isDeleted]
 * is a tombstone left by kind 9008 so a deleted group id can never be recreated.
 */
@Entity
data class GroupEntity(
    @PrimaryKey(autoGenerate = false)
    val id: String,
    val name: String = "",
    val about: String = "",
    val picture: String = "",
    val isPrivate: Boolean = false,
    val isClosed: Boolean = false,
    val isRestricted: Boolean = true,
    val isHidden: Boolean = false,
    val isDeleted: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/** [roles] is comma-separated; the only role the relay grants meaning to is "admin". */
@Entity(
    primaryKeys = ["groupId", "pubkey"],
    indices = [Index(value = ["groupId"])],
)
data class GroupMemberEntity(
    val groupId: String,
    val pubkey: String,
    val roles: String = "",
)

@Entity
data class GroupInviteEntity(
    @PrimaryKey(autoGenerate = false)
    val code: String,
    val groupId: String,
    val createdAt: Long,
)
