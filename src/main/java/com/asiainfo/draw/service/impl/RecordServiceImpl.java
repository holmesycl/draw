package com.asiainfo.draw.service.impl;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.asiainfo.draw.domain.WinningRecord;
import com.asiainfo.draw.persistence.WinningRecordMapper;
import com.asiainfo.draw.service.RecordService;

@Service("recordService")
@Transactional
public class RecordServiceImpl implements RecordService {

	@Autowired
	private WinningRecordMapper recordMapper;

	@Override
	public void saveRecord(WinningRecord... records) {
		checkArgument(records != null && records.length > 0);
		for (WinningRecord record : records) {
			record.setCreateDate(new Date());
			recordMapper.insert(record);
		}
	}

	@Override
	public void saveRecord(List<WinningRecord> records) {
		saveRecord(records.toArray(new WinningRecord[0]));
	}

}
