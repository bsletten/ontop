/*
 * Copyright (C) 2009-2013, Free University of Bozen Bolzano
 * This source code is available under the terms of the Affero General Public
 * License v3.
 * 
 * Please see LICENSE.txt for full license terms, including the availability of
 * proprietary exceptions.
 */
package it.unibz.krdb.obda.utils;

import it.unibz.krdb.obda.model.OBDADataFactory;
import it.unibz.krdb.obda.model.Predicate;
import it.unibz.krdb.obda.model.impl.OBDADataFactoryImpl;

import java.sql.Types;
import java.util.HashMap;

/**
 * This class maps SQL datatypes to XML datatypes.
 * Details look at this link: https://docs.google.com/spreadsheet/ccc?key=0AoDXwrYLJ2lvdFAtRXBPcUs2UjMtY08tZ3NYTEp5dVE&usp=sharing
 */
public class TypeMapper {

	private static TypeMapper typeMapper;

	private final HashMap<Integer, Predicate> sqlToQuest = new HashMap<Integer, Predicate>();

	private static OBDADataFactory dfac = OBDADataFactoryImpl.getInstance();

	static {
		typeMapper = new TypeMapper();
		typeMapper.put(Types.VARCHAR, dfac.getDataTypePredicateLiteral());
		typeMapper.put(Types.CHAR, dfac.getDataTypePredicateLiteral());
		typeMapper.put(Types.LONGNVARCHAR, dfac.getDataTypePredicateLiteral());
		typeMapper.put(Types.LONGVARCHAR, dfac.getDataTypePredicateLiteral());
		typeMapper.put(Types.NVARCHAR, dfac.getDataTypePredicateLiteral());
		typeMapper.put(Types.NCHAR, dfac.getDataTypePredicateLiteral());
		typeMapper.put(Types.INTEGER, dfac.getDataTypePredicateInteger());
		typeMapper.put(Types.BIGINT, dfac.getDataTypePredicateInteger());
		typeMapper.put(Types.SMALLINT, dfac.getDataTypePredicateInteger());
		typeMapper.put(Types.TINYINT, dfac.getDataTypePredicateInteger());
		typeMapper.put(Types.NUMERIC, dfac.getDataTypePredicateDecimal());
		typeMapper.put(Types.DECIMAL, dfac.getDataTypePredicateDecimal());
		typeMapper.put(Types.FLOAT, dfac.getDataTypePredicateDouble());
		typeMapper.put(Types.DOUBLE, dfac.getDataTypePredicateDouble());
		typeMapper.put(Types.REAL, dfac.getDataTypePredicateDouble());
//		typeMapper.put(Types.DATE, dfac.getDataTypePredicateDate());
//		typeMapper.put(Types.TIME, dfac.getDataTypePredicateTime());
		typeMapper.put(Types.TIMESTAMP, dfac.getDataTypePredicateDateTime());
		typeMapper.put(Types.BOOLEAN, dfac.getDataTypePredicateBoolean());
		typeMapper.put(Types.BIT, dfac.getDataTypePredicateBoolean());
//		typeMapper.put(Types.BINARY, dfac.getDataTypePredicateBinary());
//		typeMapper.put(Types.VARBINARY, dfac.getDataTypePredicateBinary());
//		typeMapper.put(Types.BLOB, dfac.getDataTypePredicateBinary());
		typeMapper.put(Types.CLOB, dfac.getDataTypePredicateLiteral());
		typeMapper.put(Types.OTHER, dfac.getDataTypePredicateLiteral());
	}

	public static TypeMapper getInstance() {
		return typeMapper;
	}

	public void put(int sqlType, Predicate questType) {
		sqlToQuest.put(sqlType, questType);
	}
	
	public Predicate getPredicate(int sqlType) {
		Predicate result = sqlToQuest.get(sqlType);
		if (result == null) {
			result = dfac.getDataTypePredicateLiteral();
		}
		return result;
	}
}
