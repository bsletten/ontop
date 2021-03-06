/*
 * Copyright (C) 2009-2013, Free University of Bozen Bolzano
 * This source code is available under the terms of the Affero General Public
 * License v3.
 * 
 * Please see LICENSE.txt for full license terms, including the availability of
 * proprietary exceptions.
 */
package it.unibz.krdb.sql.api;

public class SetUnion extends Operator {
	
	private static final long serialVersionUID = 455219736799583763L;
	
	private static final int UNION_DEFAULT = 0;
	private static final int UNION_ALL = 1;
	private static final int UNION_DISTINCT = 2;
	
	private int type;
	
	public SetUnion() {
		type = UNION_DEFAULT;
	}
	
	public SetUnion(int type) {
		setType(type);
	}
	
	public void setType(int value) {
		type = value;
	}
	
	public String getType() {
		switch(type) {
			case UNION_DEFAULT: return "union";
			case UNION_ALL: return "union all";
			case UNION_DISTINCT: return "union distinct";
		}
		return "";
	}
	
	@Override
	public String toString() {
		String str = "%s " + getType() + " %s";
		return str;
	}
}
