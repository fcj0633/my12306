package edu.swu.fcj.my12306.biz.ticketservice;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableFeignClients
@EnableScheduling
@MapperScan("edu.swu.fcj.my12306.biz.ticketservice.dao.mapper")
public class TicketServicesApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketServicesApplication.class, args);
    }
}
