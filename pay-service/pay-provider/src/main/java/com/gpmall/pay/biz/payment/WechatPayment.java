package com.gpmall.pay.biz.payment;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.gpmall.commons.result.AbstractRequest;
import com.gpmall.commons.result.AbstractResponse;
import com.gpmall.commons.tool.exception.BizException;
import com.gpmall.commons.tool.utils.TradeNoUtils;
import com.gpmall.commons.tool.utils.UtilDate;
import com.gpmall.order.OrderCoreService;
import com.gpmall.order.OrderQueryService;
import com.gpmall.order.dto.*;
import com.gpmall.pay.biz.abs.BasePayment;
import com.gpmall.pay.biz.abs.Context;
import com.gpmall.pay.biz.abs.PaymentContext;
import com.gpmall.pay.biz.abs.Validator;
import com.gpmall.pay.biz.payment.channel.wechatpay.WeChatBuildRequest;
import com.gpmall.pay.biz.payment.commons.HttpClientUtil;
import com.gpmall.pay.biz.payment.constants.PayResultEnum;
import com.gpmall.pay.biz.payment.context.AliRefundContext;
import com.gpmall.pay.biz.payment.context.WechatRefundContext;
import com.gpmall.pay.dal.entitys.Payment;
import com.gpmall.pay.dal.persistence.PaymentMapper;
import com.gpmall.user.IAddressService;
import com.gupaoedu.pay.constants.PayChannelEnum;
import com.gpmall.pay.biz.payment.constants.PaymentConstants;
import com.gpmall.pay.biz.payment.constants.WechatPaymentConfig;
import com.gpmall.pay.biz.payment.context.WechatPaymentContext;
import com.gupaoedu.pay.constants.PayReturnCodeEnum;
import com.gupaoedu.pay.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.apache.dubbo.config.annotation.Reference;
import org.apache.http.client.utils.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.*;

/**
 * 腾讯课堂搜索 咕泡学院
 * 加群获取视频：608583947
 *
 * @author 风骚的Michael 老师
 */
@Slf4j
@Service("wechatPayment")
public class WechatPayment extends BasePayment {

	@Autowired
	private WechatPaymentConfig wechatPaymentConfig;

	@Resource(name = "wechatPaymentValidator")
	private Validator validator;

	@Autowired
	private PaymentMapper paymentMapper;

	@Override
	public Validator getValidator() {
		return validator;
	}

	@Override
	public Context createContext(AbstractRequest request) {
		WechatPaymentContext wechatPaymentContext = new WechatPaymentContext();
		PaymentRequest paymentRequest = (PaymentRequest) request;
		wechatPaymentContext.setProductId(paymentRequest.getTradeNo());
		wechatPaymentContext.setSpbillCreateIp(paymentRequest.getSpbillCreateIp());
		wechatPaymentContext.setTradeType(PaymentConstants.TradeTypeEnum.NATIVE.getType());
		return wechatPaymentContext;
	}

	@Override
	public void prepare(AbstractRequest request, Context context) throws BizException {
		super.prepare(request, context);
		SortedMap paraMap = context.getsParaTemp();
		WechatPaymentContext wechatPaymentContext = (WechatPaymentContext) context;
		paraMap.put("body", wechatPaymentContext.getBody());
		paraMap.put("out_trade_no", wechatPaymentContext.getOutTradeNo());
		//单位分
		paraMap.put("total_fee", wechatPaymentContext.getTotalFee().multiply(new BigDecimal("100")).intValue());
		paraMap.put("spbill_create_ip", wechatPaymentContext.getSpbillCreateIp());
		paraMap.put("appid", wechatPaymentConfig.getWechatAppid());
		paraMap.put("mch_id", wechatPaymentConfig.getWechatMch_id());
		paraMap.put("nonce_str", WeChatBuildRequest.getNonceStr());
		paraMap.put("trade_type", wechatPaymentContext.getTradeType());
		paraMap.put("product_id", wechatPaymentContext.getProductId());
		// 此路径是微信服务器调用支付结果通知路径
		paraMap.put("device_info", "WEB");
		paraMap.put("notify_url", wechatPaymentConfig.getWechatNotifyurl());
		//二维码的失效时间（5分钟）
		paraMap.put("time_expire", UtilDate.getExpireTime(5 * 60 * 1000L));
		String sign = WeChatBuildRequest.createSign(paraMap, wechatPaymentConfig.getWechatMchsecret());
		paraMap.put("sign", sign);
		String xml = WeChatBuildRequest.getRequestXml(paraMap);
		wechatPaymentContext.setXml(xml);
	}

	@Override
	public AbstractResponse generalProcess(AbstractRequest request, Context context) throws BizException {
		PaymentResponse response = new PaymentResponse();
		WechatPaymentContext wechatPaymentContext = (WechatPaymentContext) context;
		log.info("微信支付组装的请求参数:{}",wechatPaymentContext.getXml());
		String xml = HttpClientUtil.httpPost(wechatPaymentConfig.getWechatUnifiedOrder(), wechatPaymentContext.getXml());
		log.info("微信支付同步返回的结果:{}" ,xml);
		Map<String, String> resultMap = WeChatBuildRequest.doXMLParse(xml);
		if ("SUCCESS".equals(resultMap.get("return_code"))) {
			if ("SUCCESS".equals(resultMap.get("result_code"))) {
				response.setPrepayId(resultMap.get("prepay_id"));
				response.setCodeUrl(resultMap.get("code_url"));
				response.setCode(PayReturnCodeEnum.SUCCESS.getCode());
				response.setMsg(PayReturnCodeEnum.SUCCESS.getMsg());
			} else {
				String errMsg = resultMap.get("err_code") + ":" + resultMap.get("err_code_des");
				response.setCode(PayReturnCodeEnum.PAYMENT_PROCESSOR_FAILED.getCode());
				response.setMsg(PayReturnCodeEnum.PAYMENT_PROCESSOR_FAILED.getMsg(errMsg));
			}
		} else {
			response.setCode(PayReturnCodeEnum.PAYMENT_PROCESSOR_FAILED.getCode());
			response.setMsg(PayReturnCodeEnum.PAYMENT_PROCESSOR_FAILED.getMsg(resultMap.get("return_msg")));
		}
		return response;
	}

	@Override
	public void afterProcess(AbstractRequest request, AbstractResponse respond, Context context) throws BizException {
		//插入支付表
		log.info("Alipayment begin - afterProcess -request:" + request + "\n response:" + respond);
		PaymentRequest paymentRequest = (PaymentRequest) request;
		//插入支付记录表
		PaymentResponse response = (PaymentResponse) respond;
		com.gpmall.pay.dal.entitys.Payment payment = new Payment();
		payment.setCreateTime(new Date());
		payment.setId(UUID.randomUUID().toString());
		payment.setOrderAmount(paymentRequest.getOrderFee());
		payment.setOrderId(paymentRequest.getTradeNo());
		payment.setPayerAmount(paymentRequest.getOrderFee());
		payment.setPayerUid(paymentRequest.getUserId());
		payment.setPayerName("");//TODO
		payment.setPayWay(paymentRequest.getPayChannel());
		payment.setProductName(paymentRequest.getSubject());
		payment.setStatus(PayResultEnum.TRADE_PROCESSING.getCode());//
		payment.setRemark("");
		payment.setPayNo(response.getPrepayId());//第三方的交易id
		payment.setUpdateTime(new Date());
		paymentMapper.insert(payment);
	}

	@Override
	public String getPayChannel() {
		return PayChannelEnum.WECHAT_PAY.getCode();
	}

	@Override
	public AbstractResponse completePayment(AbstractRequest request) throws BizException {
		PaymentNotifyRequest paymentNotifyRequest = (PaymentNotifyRequest) request;
		PaymentNotifyResponse response = new PaymentNotifyResponse();
		SortedMap<Object, Object> paraMap = new TreeMap<>();
		Map<String, Object> resultMap = paymentNotifyRequest.getResultMap();
		for (Iterator iter = resultMap.keySet().iterator(); iter.hasNext(); ) {
			String name = iter.next().toString();
			String value = resultMap.get(name).toString();
			paraMap.put(name, value);
		}
		//组装返回的结果的签名字符串
		String rsSign = resultMap.remove("sign").toString();
		String sign = WeChatBuildRequest.createSign(paraMap, wechatPaymentConfig.getWechatMchsecret());
		//验证签名
		if (rsSign.equals(sign)) {
			//SUCCESS、FAIL
			String resultCode = resultMap.get("result_code").toString();
			if ("SUCCESS".equals(resultCode)) {
				//TODO 更新交易表的交易结果
				response.setResult(WeChatBuildRequest.setXML("SUCCESS", "OK"));
			} else {
				//TODO 更新交易表交易结果为失败，交易失败，微信端不需要知道我们的处理结果
			}
		} else {
			throw new BizException("微信返回结果签名验证失败");
		}
		return response;
	}
}
