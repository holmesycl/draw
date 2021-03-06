package com.asiainfo.draw.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.asiainfo.draw.exception.EnterNumberErrorException;
import com.asiainfo.draw.service.DrawService;
import com.asiainfo.draw.util.Prize;

@Controller
@RequestMapping("/draw")
public class DrawController {

	private final Logger logger = LoggerFactory.getLogger(DrawController.class);

	@Autowired
	private DrawService drawService;

	@RequestMapping("/pick")
	@ResponseBody
	public Prize pick(String participantName, String enterNumber) {
		Prize prize = null;
		try {
			prize = drawService.pick(participantName, enterNumber);
		} catch (EnterNumberErrorException e) {
			prize = new Prize(Prize.EXT, "环节编码错误", "开始新的环节了，请点击右上角的姓，退出重新登录验证！");
		} catch (Exception e) {
			logger.error("系统错误，错误信息：{}", e);
			prize = Prize.createOverPrize();
		}
		logger.info("中奖信息：{}", prize);
		return prize;
	}

}
