package com.gpmall.pay.biz.payment;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.google.gson.JsonObject;
import com.gpmall.commons.result.AbstractRequest;
import com.gpmall.commons.result.AbstractResponse;
import com.gpmall.commons.tool.exception.BizException;

import com.gpmall.commons.tool.utils.TradeNoUtils;

import com.gpmall.commons.tool.utils.NumberUtils;

import com.gpmall.commons.tool.utils.UtilDate;
import com.gpmall.order.OrderCoreService;
import com.gpmall.order.OrderQueryService;
import com.gpmall.order.dto.CartProductDto;
import com.gpmall.order.dto.CreateOrderRequest;
import com.gpmall.order.dto.OrderItemRequest;
import com.gpmall.pay.biz.abs.*;
import com.gpmall.pay.biz.payment.channel.alipay.AlipayBuildRequest;
import com.gpmall.pay.biz.payment.channel.alipay.AlipayNotify;
import com.gpmall.pay.biz.payment.constants.AliPaymentConfig;
import com.gpmall.pay.biz.payment.constants.PayResultEnum;
import com.gpmall.pay.biz.payment.context.AliPaymentContext;
import com.gpmall.pay.biz.payment.context.AliRefundContext;
import com.gpmall.pay.dal.entitys.Payment;
import com.gpmall.pay.dal.entitys.PaymentExample;
import com.gpmall.pay.dal.persistence.PaymentMapper;
import com.gpmall.shopping.ICartService;
import com.gpmall.shopping.dto.CartListByIdRequest;
import com.gpmall.shopping.dto.CartListByIdResponse;
import com.gpmall.shopping.dto.CheckAllItemRequest;
import com.gpmall.shopping.dto.CheckAllItemResponse;
import com.gpmall.user.IAddressService;
import com.gpmall.user.dto.AddressDetailRequest;
import com.gpmall.user.dto.AddressDetailResponse;
import com.gpmall.user.dto.AddressDto;
import com.gpmall.user.dto.AddressListRequest;
import com.gupaoedu.pay.PayCoreService;
import com.gupaoedu.pay.constants.PayChannelEnum;
import com.gupaoedu.pay.constants.PayReturnCodeEnum;
import com.gupaoedu.pay.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.config.annotation.Reference;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 腾讯课堂搜索 咕泡学院
 * 加群获取视频：608583947
 * @author 风骚的Michael 老师
 */
@Slf4j
@Service("aliPayment")
public class AliPayment extends BasePayment {

	@Resource(name = "aliPaymentValidator")
	private Validator validator;

	@Autowired
	AliPaymentConfig aliPaymentConfig;

	@Autowired
	PaymentMapper paymentMapper;

	@Reference(timeout = 3000)
	private OrderCoreService orderCoreService;
	@Reference(timeout = 3000)
	private OrderQueryService orderQueryService;

	@Reference(timeout = 3000)
	private ICartService iCartService;

	@Reference(timeout = 3000)
	private IAddressService iAddressService;

	@Override
	public Validator getValidator() {
		return validator;
	}

	@Override
	public Context createContext(AbstractRequest request) {
		AliPaymentContext aliPaymentContext = new AliPaymentContext();
		PaymentRequest paymentRequest = (PaymentRequest) request;
		aliPaymentContext.setSubject(paymentRequest.getSubject());
		aliPaymentContext.setOutTradeNo(paymentRequest.getTradeNo());
		aliPaymentContext.setTotalFee(paymentRequest.getTotalFee());
		aliPaymentContext.setUserId(paymentRequest.getUserId());
		return aliPaymentContext;
	}

	@Override
	public void prepare(AbstractRequest request, Context context) throws BizException {
		super.prepare(request, context);
		SortedMap sParaTemp = context.getsParaTemp();
		AliPaymentContext aliPaymentContext = (AliPaymentContext) context;
		PaymentRequest paymentRequest = (PaymentRequest) request;
		sParaTemp.put("partner", aliPaymentConfig.getAli_partner());
		sParaTemp.put("input_charset", aliPaymentConfig.getInput_charset());
		sParaTemp.put("service", aliPaymentConfig.getAli_service());
		sParaTemp.put("seller_email", aliPaymentConfig.getSeller_email());
		sParaTemp.put("seller_id", aliPaymentConfig.getSeller_id());
		sParaTemp.put("payment_type", 1);
		sParaTemp.put("it_b_pay", aliPaymentConfig.getIt_b_pay());
		sParaTemp.put("notify_url", aliPaymentConfig.getNotify_url());
		sParaTemp.put("return_url", aliPaymentConfig.getReturn_url());
		sParaTemp.put("out_trade_no", aliPaymentContext.getOutTradeNo());
		sParaTemp.put("subject", aliPaymentContext.getSubject());
		sParaTemp.put("total_fee", aliPaymentContext.getTotalFee());
		aliPaymentContext.setsParaTemp(sParaTemp);
	}


	@Override
	public AbstractResponse generalProcess(AbstractRequest request, Context context) throws BizException {
		Map<String, Object> sPara = AlipayBuildRequest.buildRequestParam(context.getsParaTemp(), aliPaymentConfig);
		log.info("支付宝支付组装请求参数:{}", JSON.toJSONString(sPara));
		String strPara = AlipayBuildRequest.buildRequest(sPara, "get", "确认", aliPaymentConfig);
		log.info("支付宝支付同步返回的表单:{}", JSON.toJSONString(strPara));
		PaymentResponse response = new PaymentResponse();
		response.setCode(PayReturnCodeEnum.SUCCESS.getCode());
		response.setMsg(PayReturnCodeEnum.SUCCESS.getMsg());
		response.setHtmlStr(strPara);
		return response;
	}


	@Override
	public void afterProcess(AbstractRequest request, AbstractResponse respond, Context context) throws BizException {
		log.info("Alipayment begin - afterProcess -request:" + request + "\n response:" + respond);
		PaymentRequest paymentRequest = (PaymentRequest) request;

		//插入支付记录表
		com.gpmall.pay.dal.entitys.Payment payment = new Payment();
		payment.setCreateTime(new Date());
		payment.setOrderAmount(paymentRequest.getOrderFee());
		payment.setOrderId(paymentRequest.getTradeNo());
		payment.setPayerAmount(paymentRequest.getOrderFee());

		PaymentResponse response = (PaymentResponse) respond;
		Payment payment = new Payment();
		payment.setCreateTime(new Date());
		payment.setId(UUID.randomUUID().toString());
		BigDecimal amount = new BigDecimal(paymentRequest.getOrderFee() / 100);
		payment.setOrderAmount(NumberUtils.toDouble(amount));
		payment.setOrderId(paymentRequest.getTradeNo());
		payment.setPayerAmount(NumberUtils.toDouble(amount));

		payment.setPayerUid(paymentRequest.getUserId());
		payment.setPayerName("");//TODO
		payment.setPayWay(paymentRequest.getPayChannel());
		payment.setProductName(paymentRequest.getSubject());
		payment.setStatus(PayResultEnum.TRADE_PROCESSING.getCode());//
		payment.setRemark("支付宝支付");
		payment.setUpdateTime(new Date());
		paymentMapper.insert(payment);
	}

	@Override
	public String getPayChannel() {
		return PayChannelEnum.ALI_PAY.getCode();
	}

	/**
	 * 回调通知处理
	 * @param request
	 * @return
	 * @throws BizException
	 */
	@Override
	@Transactional
	public AbstractResponse completePayment(AbstractRequest request) throws BizException {
		PaymentNotifyRequest paymentNotifyRequest = (PaymentNotifyRequest) request;

		Map requestParams = paymentNotifyRequest.getResultMap();
		Map<String, Object> params = new HashMap<>(requestParams.size());
		requestParams.forEach((key, value) -> {
			String[] values = (String[]) value;
			params.put((String) key, StringUtils.join(values, ","));
		});

		PaymentNotifyResponse response = new PaymentNotifyResponse();

		PaymentExample paymentExample = new PaymentExample();
		PaymentExample.Criteria criteria = paymentExample.createCriteria();
		String orderId = params.get("out_trade_no").toString();
		criteria.andOrderIdEqualTo(orderId);
		//验证
		if (AlipayNotify.verify(params, aliPaymentConfig)) {
			com.gpmall.pay.dal.entitys.Payment payment = new Payment();
			payment.setPayNo(params.get("trade_no").toString());
			//TRADE_FINISH(支付完成)、TRADE_SUCCESS(支付成功)、FAIL(支付失败)
			String tradeStatus = params.get("trade_status").toString();
			if ("TRADE_SUCCESS".equals(tradeStatus)) {
				//更新支付表
				payment.setStatus(PayResultEnum.TRADE_SUCCESS.getCode());
				payment.setPaySuccessTime((Date) params.get("gmt_payment"));
				paymentMapper.updateByExampleSelective(payment, paymentExample);
				//更新订单表状态
				orderCoreService.updateOrder(1, orderId);
				response.setResult("success");
				return response;
			} else if ("TRADE_FINISH".equals(tradeStatus)) {
				payment.setStatus(PayResultEnum.TRADE_FINISHED.getCode());
				paymentMapper.updateByExampleSelective(payment, paymentExample);
				//更新订单表状态
				orderCoreService.updateOrder(1, orderId);
				response.setResult("success");
			} else if ("FAIL".equals(tradeStatus)) {
				payment.setStatus(PayResultEnum.FAIL.getCode());
				paymentMapper.updateByExampleSelective(payment, paymentExample);
				response.setResult("success");
			} else {
				response.setResult("fail");
			}
		} else {
			throw new BizException("支付宝签名验证失败");
		}
		return response;
	}
}
