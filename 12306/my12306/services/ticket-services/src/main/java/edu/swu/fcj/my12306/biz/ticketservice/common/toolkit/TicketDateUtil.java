package edu.swu.fcj.my12306.biz.ticketservice.common.toolkit;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 日期工具：缓存/响应中的时间格式化与历时计算
 */
public final class TicketDateUtil {

    /**
     * 计算小时差，输出 HH:mm（如 03:24）
     */
    public static String calculateHourDifference(Date startTime, Date endTime) {
        LocalDateTime startDateTime = toLocalDateTime(startTime);
        LocalDateTime endDateTime = toLocalDateTime(endTime);
        Duration duration = Duration.between(startDateTime, endDateTime);
        return String.format("%02d:%02d", duration.toHours(), duration.toMinutes() % 60);
    }

    /**
     * 按模式格式化时间，如 HH:mm、MM-dd HH:mm
     */
    public static String convertDateToLocalTime(Date date, String pattern) {
        return toLocalDateTime(date).format(DateTimeFormatter.ofPattern(pattern));
    }

    private static LocalDateTime toLocalDateTime(Date date) {
        return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
    }

    private TicketDateUtil() {
    }
}
