package com.gpmall.order.services;/**
 * Created by mic on 2019/7/30.
 */

import com.gpmall.order.OrderCoreService;
import com.gpmall.order.biz.TransOutboundInvoker;
import com.gpmall.order.biz.context.AbsTransHandlerContext;
import com.gpmall.order.biz.factory.OrderProcessPipelineFactory;
import com.gpmall.order.constant.OrderRetCode;
import com.gpmall.order.constants.OrderConstants;
import com.gpmall.order.dal.entitys.Order;
import com.gpmall.order.dal.entitys.OrderExample;
import com.gpmall.order.dal.persistence.OrderItemMapper;
import com.gpmall.order.dal.persistence.OrderMapper;
import com.gpmall.order.dal.persistence.OrderShippingMapper;
import com.gpmall.order.dto.*;
import com.gpmall.order.utils.ExceptionProcessorUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.common.utils.CollectionUtils;
import org.apache.dubbo.config.annotation.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tk.mybatis.mapper.entity.Example;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

/**
 * 腾讯课堂搜索【咕泡学院】
 * 官网：www.gupaoedu.com
 * 风骚的Mic 老师
 * create-date: 2019/7/30-上午10:05
 */
@Slf4j
@Service(cluster = "failfast")
public class OrderCoreServiceImpl implements OrderCoreService {

	@Autowired
	OrderMapper orderMapper;

	@Autowired
	OrderItemMapper orderItemMapper;

	@Autowired
	OrderShippingMapper orderShippingMapper;

	@Autowired
	OrderProcessPipelineFactory orderProcessPipelineFactory;

    @Autowired
    OrderCoreService orderCoreService;


	/**
	 * 创建订单的处理流程
	 *
	 * @param request
	 * @return
	 */
	@Override
	public CreateOrderResponse createOrder(CreateOrderRequest request) {
		CreateOrderResponse response = new CreateOrderResponse();
		try {
			TransOutboundInvoker invoker = orderProcessPipelineFactory.build(request);
			invoker.start(); //启动流程（pipeline来处理）
			AbsTransHandlerContext context = invoker.getContext();
			response = (CreateOrderResponse) context.getConvert().convertCtx2Respond(context);
		} catch (Exception e) {
			log.error("OrderCoreServiceImpl.createOrder Occur Exception :" + e);
			ExceptionProcessorUtils.wrapperHandlerException(response, e);
		}
		return response;
	}

	/**
	 * 取消订单
	 *
	 * @param request
	 * @return
	 */
	@Override
	public CancelOrderResponse cancelOrder(CancelOrderRequest request) {
		CancelOrderResponse response = new CancelOrderResponse();
		try {
			Order order = new Order();
			order.setOrderId(request.getOrderId());
			order.setStatus(OrderConstants.ORDER_STATUS_TRANSACTION_CANCEL);
			order.setCloseTime(new Date());
			int num = orderMapper.updateByPrimaryKey(order);
			log.info("cancelOrder,effect Row:" + num);
			response.setCode(OrderRetCode.SUCCESS.getCode());
			response.setMsg(OrderRetCode.SUCCESS.getMessage());
		} catch (Exception e) {
			log.error("OrderCoreServiceImpl.cancelOrder Occur Exception :" + e);
			ExceptionProcessorUtils.wrapperHandlerException(response, e);
		}
		return response;
	}



	/**
	 * 删除订单
	 *
	 * @param request
	 * @return
	 */
	@Override
	public DeleteOrderResponse deleteOrder(DeleteOrderRequest request) {
		DeleteOrderResponse response = new DeleteOrderResponse();
		try {
			request.requestCheck();
			deleteOrderWithTransaction(request);
			response.setCode(OrderRetCode.SUCCESS.getCode());
			response.setMsg(OrderRetCode.SUCCESS.getMessage());
		} catch (Exception e) {
			log.error("OrderCoreServiceImpl.deleteOrder Occur Exception :" + e);
			ExceptionProcessorUtils.wrapperHandlerException(response, e);
		}
		return response;
	}



	@Override
	public void updateOrder(Integer status, String orderId) {
		Order order = new Order();
		order.setOrderId(orderId);
		order.setStatus(status);
		orderMapper.updateByPrimaryKey(order);
	}


    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteOrderWithTransaction(DeleteOrderRequest request){
        orderMapper.deleteByPrimaryKey(request.getOrderId());
        Example example = new Example(Order.class);
        example.createCriteria().andEqualTo("orderId",request.getOrderId());
        orderItemMapper.deleteByExample(example);
        orderShippingMapper.deleteByPrimaryKey(request.getOrderId());
    }

    /**
     * @Description: first 创建时间早于当前时间的过去两小时，即判定为超时未支付，关闭订单
     * @Author: Douglas Lai(990593218)
     * @Date: 2019/08/15
     */
    @Override
    public void closeOrder(){
        OrderExample example = new OrderExample();
        //LocalDateTime转Date
        LocalDateTime localDateTime = LocalDateTime.now();
        // TODO 后面需要改成nacos配置中心取值
        Instant instant = localDateTime.minusHours(2L).atZone(ZoneId.systemDefault()).toInstant();
        Date date = Date.from(instant);
        example.createCriteria()
                .andCreateTimeLessThan(date)
                .andStatusEqualTo(OrderConstants.ORDER_STATUS_INIT)
                .andPaymentTimeIsNull();

        example.setOrderByClause("create_time desc");
        List<Order> orderList = orderMapper.selectByExample(example);
        if(CollectionUtils.isEmpty(orderList)){
            // TODO
            log.info("没有超时订单需要处理...");
            return;
        }

        orderList.forEach(order -> {
            order.setStatus(OrderConstants.ORDER_STATUS_TRANSACTION_CLOSE);
            order.setCloseTime(new Date());
            int num=orderMapper.updateByPrimaryKey(order);
            log.info("closeOrder,effect Row:"+num);
        });
    }
}
