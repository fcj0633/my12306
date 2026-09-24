package edu.swu.fcj.my12306.biz.userservice.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * MyBatis-Plus 公共字段自动填充
 * <p>
 * BaseDO 上的 @TableField(fill = ...) 只是"声明"，必须有本 Handler 才会在 insert/update 时真正赋值。
 */
@Component
public class MyMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        // 插入时填充创建时间、修改时间与逻辑删除标识。
        // delFlag 必须填 0：MyBatis-Plus 逻辑删除查询会自动追加 del_flag = 0，
        // 若为 NULL，新插入的数据会匹配不上查询条件（注册后查不到自己的账号）。
        this.strictInsertFill(metaObject, "createTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "updateTime", Date.class, new Date());
        this.strictInsertFill(metaObject, "delFlag", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 更新时刷新修改时间
        this.strictUpdateFill(metaObject, "updateTime", Date.class, new Date());
    }
}
