package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * @author pj567
 * @date :2021/1/7
 * @description:
 */
@Dao
public interface VodCollectDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(VodCollect record);

    @Query("select * from vodCollect  order by updateTime desc")
    List<VodCollect> getAll();

    @Query("select * from vodCollect where `id`=:id")
    VodCollect getVodCollect(int id);

    @Query("delete from vodCollect where `id`=:id")
    void delete(int id);

    @Query("select * from vodCollect where `sourceKey`=:sourceKey and `vodId`=:vodId")
    VodCollect getVodCollect(String sourceKey, String vodId);

    /** 按主键更新（合并同步时用于把较新的名称/海报写回已有记录，保留原 id） */
    @Update
    int update(VodCollect record);

    @Delete
    int delete(VodCollect record);

    @Query("DELETE FROM vodCollect")
    void deleteAll();
}