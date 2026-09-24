package edu.swu.fcj.my12306.biz.payservice.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import edu.swu.fcj.my12306.biz.payservice.dao.entity.PayDO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PayMapper extends BaseMapper<PayDO> {
}
