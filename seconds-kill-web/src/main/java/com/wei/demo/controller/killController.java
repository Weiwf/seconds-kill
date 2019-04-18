package com.wei.demo.controller;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.wei.demo.annotation.CountVisitNum;
import com.wei.demo.annotation.RateLimit;
import com.wei.demo.constant.RedisConstant;
import com.wei.demo.constant.TempUserConstant;
import com.wei.demo.entity.Order;
import com.wei.demo.entity.Stock;
import com.wei.demo.mq.Producer;
import com.wei.demo.service.IOrderService;
import com.wei.demo.service.IStockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * @author weiwenfeng
 * @date 2019/4/14
 */
@RestController
@RequestMapping("/kill")
public class killController {

    private static final Logger logger = LoggerFactory.getLogger(killController.class);

    @Reference
    private IOrderService orderService;

    @Reference
    private IStockService stockService;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private Producer producer;

    @GetMapping("/getAllOrders")
    public List<Order> getAllOrders() {
        return orderService.selectAllOrders();
    }

    @GetMapping("/getAllStocks")
    public List<Stock> getAllStocks() {
        return stockService.selectAllStocks();
    }

    @GetMapping("/loadDataFromDBtoRedis")
    @CountVisitNum(key = "loadDataFromDBtoRedis")
    public void loadDataFromDBtoRedis(){
        List<Stock> stocks = stockService.selectAllStocks();
        if (null != stocks && stocks.size() != 0) {
            for (Stock stock : stocks) {
                int sid = stock.getId();
                redisTemplate.opsForValue().set(RedisConstant.STOCK_KEY_PREFIX + sid, JSON.toJSONString(stock));
            }
        }
    }

    @RequestMapping("/killGoods/{sid}")
    @ResponseBody
    @RateLimit(key = "killGoods", time = 1, count = 50)
    public void killGoods(@PathVariable int sid) {
        saleFromRedis(sid);
    }

    /**
     * 从redis查询库存并修改，同时也要利用数据库的乐观锁防止“超卖”现象，
     * 需要注意的是保证数据库和redis的数据一致
     * @param sid
     */
    private void saleFromRedis(int sid) {
        Stock stock = checkStockFromRedis(sid);
        //异步操作，将减库存的消息发送到消息队列中
        producer.sendOrderMessage("dec:stock:",null,JSON.toJSONBytes(stock), TempUserConstant.USER_ID);
    }

    /**
     * 从Redis中检查库存
     * @param sid
     * @return
     */
    private Stock checkStockFromRedis(int sid) {
        String stockJson = redisTemplate.opsForValue().get(RedisConstant.STOCK_KEY_PREFIX + sid);
        Stock stock = JSON.parseObject(stockJson,new TypeReference<Stock>(){});
        if (stock.getCount().equals(stock.getSale())) {
            throw new RuntimeException("库存不足！");
        }
        return stock;
    }

}
