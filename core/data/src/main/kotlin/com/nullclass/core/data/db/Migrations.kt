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

/**
 * v3 → v4：加入考试表。
 *
 * 考试通过 courseId 绑定课程，课程的 termId 仍是考试的学期归属来源；不重复存 termId，
 * 避免课程被复制/迁移后出现「考试挂着旧学期」的两份事实。
 */
val MIGRATION_3_4 = object : Migration(3, 4) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `exams` (" +
                "`id` TEXT NOT NULL, `courseId` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                "`dateEpochDay` INTEGER NOT NULL, `startMinuteOfDay` INTEGER, " +
                "`endMinuteOfDay` INTEGER, `location` TEXT, `seat` TEXT, `note` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                "PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`courseId`) REFERENCES `courses`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exams_courseId` ON `exams` (`courseId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_exams_deletedAt` ON `exams` (`deletedAt`)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_exams_courseId_dateEpochDay_deletedAt` " +
                "ON `exams` (`courseId`, `dateEpochDay`, `deletedAt`)",
        )
    }
}

/**
 * v4 → v5：加入跳过日期表（手动添加 + 节假日同步共写，纯本地、不同步）。
 * 纯新增表，无存量数据迁移。
 */
val MIGRATION_4_5 = object : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `skip_dates` (" +
                "`epochDay` INTEGER NOT NULL, `type` TEXT NOT NULL, `label` TEXT, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_skip_dates_type` ON `skip_dates` (`type`)")
    }
}

/**
 * v5 → v6：加入串课表（调休时某天改上另一天的课，纯本地、不同步）。
 * 纯新增表，无存量数据迁移。
 */
val MIGRATION_5_6 = object : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `day_overrides` (" +
                "`epochDay` INTEGER NOT NULL, `sourceEpochDay` INTEGER NOT NULL, " +
                "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`epochDay`))",
        )
    }
}
