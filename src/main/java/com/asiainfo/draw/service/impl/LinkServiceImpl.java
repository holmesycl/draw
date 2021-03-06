package com.asiainfo.draw.service.impl;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.asiainfo.draw.cache.CommandCache;
import com.asiainfo.draw.cache.CurrentLinkCache;
import com.asiainfo.draw.cache.CurrentLinkCache.LinkState;
import com.asiainfo.draw.cache.ParticipantCache;
import com.asiainfo.draw.domain.DrawLink;
import com.asiainfo.draw.domain.DrawLinkExample;
import com.asiainfo.draw.domain.DrawPrize;
import com.asiainfo.draw.domain.DrawPrizeExample;
import com.asiainfo.draw.domain.LinkItem;
import com.asiainfo.draw.domain.LinkMember;
import com.asiainfo.draw.domain.Participant;
import com.asiainfo.draw.domain.PrizeItem;
import com.asiainfo.draw.domain.WinningRecord;
import com.asiainfo.draw.exception.StartLinkException;
import com.asiainfo.draw.persistence.DrawLinkMapper;
import com.asiainfo.draw.persistence.DrawPrizeMapper;
import com.asiainfo.draw.service.LinkMemberService;
import com.asiainfo.draw.service.LinkService;
import com.asiainfo.draw.service.ParticipantService;
import com.asiainfo.draw.service.RecordService;
import com.asiainfo.draw.util.Command;
import com.asiainfo.draw.util.DefaultPrizePoolFactory;
import com.asiainfo.draw.util.PrizePool;
import com.asiainfo.draw.util.PrizePoolFactory;

@Service("linkService")
@Transactional
public class LinkServiceImpl implements LinkService {

	private final Logger logger = LoggerFactory.getLogger(LinkServiceImpl.class);

	@Autowired
	private DrawLinkMapper linkMapper;

	@Autowired
	private DrawPrizeMapper prizeMapper;

	@Autowired
	private LinkMemberService memberService;

	@Autowired
	private CurrentLinkCache currentLinkCache;

	@Autowired
	private ParticipantCache participantCache;

	@Autowired
	private RecordService recordService;

	@Autowired
	private ParticipantService participantService;

	@Autowired
	private CommandCache commandCache;

	@Override
	public void initLink(Integer linkId) {

		logger.info("环节初始化->尝试结束当前环节...");
		finishCurrentLink();
		logger.info(">>清空当前环节缓存...");
		currentLinkCache.invalidateAll();

		logger.info("<<===========读取新的环节...");
		// 获取下一环节
		DrawLink currentLink = linkMapper.selectByPrimaryKey(linkId);
		if (currentLink == null) {
			throw new RuntimeException("根据环节ID:" + linkId + "获取不到抽奖环节");
		}

		logger.info("环节初始化->当前环节:{}", currentLink.getLinkName());
		currentLinkCache.put(CurrentLinkCache.CURRENT_LINK, currentLink);

		// 刚初始化时，当前环节不能抽奖
		logger.info("环节初始化->环节状态设置为：{}.", LinkState.INIT);
		currentLinkCache.put(CurrentLinkCache.CURRENT_STATE, LinkState.INIT);

		// ----------------------------------------------------------------------------
		// 修改数据库的环节状态为2(进行中)
		currentLink.setLinkState(2);
		linkMapper.updateByPrimaryKeySelective(currentLink);

		// 开始时，进入主界面
		commandCache.put(CommandCache.CURRENT_COMMAND, Command.redirect("screen.jsp"));
	}

	/**
	 * 初始化奖品池，一般在选人后才进行初始化
	 */
	@Override
	public void initPool() {

		DrawLink link = null;
		try {
			link = (DrawLink) currentLinkCache.get(CurrentLinkCache.CURRENT_LINK);
		} catch (Exception e) {
			logger.info(">>当前环节不存在！详细信息:{}", e);
		}
		if (link != null) {

			List<DrawPrize> prizes = getPrizeByLink(link.getLinkId());
			if (prizes == null || prizes.size() == 0) {
				logger.warn("环节:{}没有配置奖品！", link.getLinkName());
				prizes = new ArrayList<DrawPrize>();
			}
			int numberOfPrize = 0;
			for (DrawPrize prize : prizes) {
				if (prize != null) {
					numberOfPrize += prize.getSize();
				}
			}
			logger.info(">>当前环节配置的奖品数量:{}", numberOfPrize);

			/* 确认抽奖用户 */
			List<LinkMember> members = memberService.getMemberByLinkIdAndState(link.getLinkId(), LinkMember.STATE_CREATE);
			if (members == null || members.size() == 0) {
				throw new RuntimeException("抽奖参与人员不能为空");
			}
			int numberOfPerson = members.size();
			logger.info(">>当前参与人员数量:{}", numberOfPerson);
			memberService.confirm(members);

			/* 抽奖机会减少1次 */
			Set<Participant> participants = new HashSet<Participant>();
			for (LinkMember member : members) {
				Participant participant = participantService.getByParticipantId(member.getParticipantId());
				participants.add(participant);
			}
			participantService.subShakeTime(participants);

			// 如果参与人数大于奖品数
			if (numberOfPerson > numberOfPrize) {
				try {
					DrawPrize prize = getPrizeByLink(0).get(0);
					prize.setSize(numberOfPerson - numberOfPrize);
					prizes.add(prize);
					logger.info(">>当前环节参与人员数量大于配置的奖品数量，加入默认的奖品数量:{}", numberOfPerson - numberOfPrize);
				} catch (Exception e) {
					logger.error(">>没有配置默认环节奖品。默认环节奖品的环节ID为0，只能配置一条数据。");
				}
			}

			logger.info("<<===========初始化奖品池...");
			PrizePoolFactory poolFactory = new DefaultPrizePoolFactory();
			PrizePool pool = poolFactory.createPrizePools(prizes);

			logger.info("<<===========把奖品池加入缓存中...");
			currentLinkCache.put(CurrentLinkCache.CURRENT_POOL, pool);
		}
	}

	/**
	 * 根据环节ID查询环节奖品
	 * 
	 * @param linkId
	 *            环节ID
	 * @return 当前环节的所有奖品
	 */
	private List<DrawPrize> getPrizeByLink(Integer linkId) {
		checkNotNull(linkId);
		DrawPrizeExample prizeExample = new DrawPrizeExample();
		// 环节ID相等
		prizeExample.createCriteria().andLinkIdEqualTo(linkId);
		List<DrawPrize> prizes = prizeMapper.selectByExample(prizeExample);
		return prizes;
	}

	/**
	 * 结束当前环节
	 */
	private void finishCurrentLink() {
		DrawLink currentLink = null;
		try {
			currentLink = (DrawLink) currentLinkCache.get(CurrentLinkCache.CURRENT_LINK);
		} catch (Exception e) {
			logger.error(">>环节已结束！");
		}
		if (currentLink != null) {
			// 把当前环节的开关关闭
			currentLinkCache.put(CurrentLinkCache.CURRENT_STATE, LinkState.FINISH);
			// 清空当前缓存
			currentLinkCache.invalidateAll();
			// 环节状态设置为已结束
			finishLink(currentLink.getLinkId());
		}
	}

	@Override
	public void finishLink(Integer linkId) {
		checkNotNull(linkId);
		DrawLink link = linkMapper.selectByPrimaryKey(linkId);
		link.setLinkState(3); // 环节标志设置为3(已结束)
		linkMapper.updateByPrimaryKeySelective(link);
	}

	@Override
	public void startCurrentLink() {
		logger.info("<<========尝试启动新的抽奖环节...");
		// 当前环节还没有结束时，不能启动新的环节
		LinkState linkState = (LinkState) currentLinkCache.get(CurrentLinkCache.CURRENT_STATE);
		logger.info("<<=============当前环节状态:{}", linkState);
		if (linkState == LinkState.RUN) {
			String msg = "当前环节已经处于启动状态，启动失败...";
			logger.info(msg);
			throw new StartLinkException(msg);
		} else if (linkState == LinkState.FINISH) {
			String msg = "新的环节未初始化，启动失败...";
			logger.info(msg);
			throw new StartLinkException(msg);
		} else if (linkState == LinkState.INIT) {
			logger.info("环节开始->把当前环节的状态设置为:{}", LinkState.RUN);
			// 把当前环节的开关打开
			currentLinkCache.put(CurrentLinkCache.CURRENT_STATE, LinkState.RUN);
		}
	}

	/**
	 * 获取当前环节用户中奖纪录
	 * 
	 * @return
	 */
	@Override
	public List<WinningRecord> getCurrnetLinkHitPrize() {
		// 当前环节
		DrawLink currentLink = null;
		try {
			currentLink = (DrawLink) currentLinkCache.get(CurrentLinkCache.CURRENT_LINK);
		} catch (Exception e) {
			logger.info("当前环节已结束！");
		}
		List<WinningRecord> records = new ArrayList<WinningRecord>();
		// 当前环节进行中时，才有必要返回当前环节的中奖纪录
		if (currentLink != null) {
			// 环节中奖纪录
			records = recordService.getRecordByParticipantNameAndLinkId(null, currentLink.getLinkId());
		}
		return records;
	}

	@Override
	public DrawLink getCurrentLink() {
		return (DrawLink) currentLinkCache.get(CurrentLinkCache.CURRENT_LINK);
	}

	@Override
	public List<DrawLink> getAll() {
		DrawLinkExample linkExample = new DrawLinkExample();
		List<DrawLink> links = linkMapper.selectByExample(linkExample);
		return links;
	}

	@Override
	public void resetLink(Integer linkId) {
		// 重置环节意味着，把已经结束的环节状态置为1（未开始）
		DrawLink link = linkMapper.selectByPrimaryKey(linkId);
		if (link == null) {
			throw new NullPointerException("环节为空");
		}
		link.setLinkState(1);
		linkMapper.updateByPrimaryKeySelective(link);
	}

	@Override
	public void add(LinkItem item) {
		// 新增抽奖环节
		DrawLink link = new DrawLink();
		link.setLinkName(item.getLinkName());
		// 只对未中奖的人开放
		link.setOpenState(1);
		// 未开始状态
		link.setLinkState(1);

		link.setLinkOrder(0);

		String enterNumber = item.getEnterNumber();
		// 环节验证码为空时，默认设置为123456
		if (StringUtils.isBlank(item.getEnterNumber())) {
			enterNumber = "123456";
		}
		// 环节进入编码
		link.setEnterNumber(enterNumber);
		linkMapper.insert(link);

		// 目的：为了获取新增数据的ID
		DrawLinkExample linkExample = new DrawLinkExample();
		linkExample.createCriteria().andLinkNameEqualTo(item.getLinkName()).andEnterNumberEqualTo(link.getEnterNumber());
		link = linkMapper.selectByExample(linkExample).get(0);

		// 新增奖品
		List<PrizeItem> prizeItems = item.getPrizeItems();
		if (prizeItems != null && prizeItems.size() > 0) {
			for (PrizeItem prizeItem : prizeItems) {
				DrawPrize prize = new DrawPrize();
				prize.setLinkId(link.getLinkId());
				prize.setPrizeName(prizeItem.getPrizeName());
				prize.setPrizeType(prizeItem.getPrizeType());
				prize.setSize(prizeItem.getSize());
				prizeMapper.insert(prize);
			}
		}

	}

}
