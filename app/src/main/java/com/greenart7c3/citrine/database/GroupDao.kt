package com.greenart7c3.citrine.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface GroupDao {
    @Query("SELECT * FROM GroupEntity")
    suspend fun getAllGroups(): List<GroupEntity>

    @Query("SELECT * FROM GroupMemberEntity")
    suspend fun getAllMembers(): List<GroupMemberEntity>

    @Query("SELECT * FROM GroupInviteEntity")
    suspend fun getAllInvites(): List<GroupInviteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroup(group: GroupEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMember(member: GroupMemberEntity)

    @Query("DELETE FROM GroupMemberEntity WHERE groupId = :groupId AND pubkey = :pubkey")
    suspend fun deleteMember(groupId: String, pubkey: String)

    @Query("DELETE FROM GroupMemberEntity WHERE groupId = :groupId")
    suspend fun deleteMembers(groupId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvite(invite: GroupInviteEntity)

    @Query("DELETE FROM GroupInviteEntity WHERE groupId = :groupId")
    suspend fun deleteInvites(groupId: String)

    // Tombstones the group (blocks id reuse) and clears its membership and invites.
    @Transaction
    suspend fun tombstoneGroup(group: GroupEntity) {
        upsertGroup(group.copy(isDeleted = true))
        deleteMembers(group.id)
        deleteInvites(group.id)
    }
}
