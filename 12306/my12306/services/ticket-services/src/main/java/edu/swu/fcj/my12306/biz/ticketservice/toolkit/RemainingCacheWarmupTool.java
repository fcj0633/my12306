package edu.swu.fcj.my12306.biz.ticketservice.toolkit;

import com.alibaba.fastjson2.JSON;
import edu.swu.fcj.my12306.biz.ticketservice.TicketServicesApplication;
import edu.swu.fcj.my12306.biz.ticketservice.common.cache.RedisCacheHelper;
import edu.swu.fcj.my12306.biz.ticketservice.common.enums.VehicleTypeEnum;
import edu.swu.fcj.my12306.biz.ticketservice.dao.mapper.TrainMapper;
import edu.swu.fcj.my12306.biz.ticketservice.service.cache.SeatMarginCacheLoader;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit operator tool; never scheduled. OD input is a JSON array of [departure, arrival]. */
public final class RemainingCacheWarmupTool {
    public static void main(String[] args)throws Exception {
        if(args.length<2||args.length>4)throw new IllegalArgumentException("Usage: trainId ods.json [concurrency=2] [ODsPerSecond=10]");
        String trainId=Long.valueOf(args[0]).toString();
        int concurrency=args.length>2?Integer.parseInt(args[2]):2;
        double rate=args.length>3?Double.parseDouble(args[3]):10;
        if(concurrency<1||concurrency>32||!Double.isFinite(rate)||rate<=0||rate>100)throw new IllegalArgumentException("Invalid concurrency or rate");
        var ods=JSON.parseArray(Files.readString(Path.of(args[1])));
        for(Object value:ods){var pair=JSON.parseArray(JSON.toJSONString(value));if(pair.size()!=2||pair.getString(0).isBlank()||pair.getString(1).isBlank())throw new IllegalArgumentException("Each OD requires two station names");}
        try(var context=new SpringApplicationBuilder(TicketServicesApplication.class).web(WebApplicationType.NONE).run(
                "--spring.cloud.nacos.discovery.enabled=false","--spring.cloud.discovery.enabled=false",
                "--my12306.ticket.orphan-scan-enabled=false","--my12306.pay.notify-mode=feign")){
            var cache=context.getBean(RedisCacheHelper.class);var train=cache.train(trainId,context.getBean(TrainMapper.class));
            if(train==null)throw new IllegalArgumentException("Train does not exist");
            int type=VehicleTypeEnum.findSeatTypesByCode(train.getTrainType()).getFirst();
            var loader=context.getBean(SeatMarginCacheLoader.class);var slots=new Semaphore(concurrency);var futures=new ArrayList<Future<?>>();
            long start=System.nanoTime(),interval=(long)(1e9/rate),nextStart=start;
            try(var pool=Executors.newFixedThreadPool(concurrency)){
                for(Object value:ods){var pair=JSON.parseArray(JSON.toJSONString(value));slots.acquire();
                    long wait=nextStart-System.nanoTime();if(wait>0)TimeUnit.NANOSECONDS.sleep(wait);
                    nextStart=System.nanoTime()+interval;
                    futures.add(pool.submit(()->{try{loader.load(trainId,type,pair.getString(0),pair.getString(1));}finally{slots.release();}}));
                }
                for(var future:futures)future.get();
            }
            System.out.println("Completed "+ods.size()+" OD loads in "+(System.nanoTime()-start)/1e9+" seconds");
        }
    }
}
