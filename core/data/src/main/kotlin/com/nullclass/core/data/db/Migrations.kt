package com.nullclass.core.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nullclass.core.model.DefaultTimetable

/**
 * v2 → v3：课表成为一等对象。
 *
 *  - 新建 `timetables` 表（审计三列 + 软删除墓碑，与全业务表一致）
 *  - `terms` 加 `timetableId`（NOT NULL，默认空串——空串只在「还没归到任何课表」的
 *    过渡态出现，SyncEngine 落库前会解析掉）
 *  - 存量学期全部归到固定 UUID 的默认课表「我的课表」（见 [DefaultTimetable]）
 *  - 仅当 `terms` 表非空才建默认课表：空库（装了没用过）直接走首次启动引导
 *
 * Room 对「实体没声明 defaultValue 的列」不校验库里的 DEFAULT 子句，
 * 所以 ADD COLUMN 带 DEFAULT '' 不会触发 schema 校验失败（反向才会）。
 */
val MIGRATION_2_3 = object : Migration(2, 3) {

    override fun migrate(db: SupportSQLiteDatabase) {
        val now = System.currentTimeMillis()

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `timetables` (" +
                "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_timetables_deletedAt` ON `timetables` (`deletedAt`)")

        db.execSQL("ALTER TABLE `terms` ADD COLUMN `timetableId` TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_terms_timetableId` ON `terms` (`timetableId`)")

        db.execSQL(
            "INSERT INTO `timetables` (`id`, `name`, `createdAt`, `updatedAt`, `deletedAt`) " +
                "SELECT '${DefaultTimetable.ID}', '${DefaultTimetable.NAME}', $now, $now, NULL " +
                "WHERE EXISTS (SELECT 1 FROM `terms`)",
        )
        db.execSQL(
            "UPDATE `terms` SET `timetableId` = '${DefaultTimetable.ID}' " +
                "WHERE EXISTS (SELECT 1 FROM `terms`)",
        )
    }
}
