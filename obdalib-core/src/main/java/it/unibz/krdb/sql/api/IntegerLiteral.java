package it.unibz.krdb.sql.api;

public class IntegerLiteral extends NumericLiteral {

	/**
	 * Integer value
	 */
	protected Integer value;

	/**
	 * Constructor
	 * 
	 * @param value
	 *            Integer value
	 */
	public IntegerLiteral(String value) {
		set(new Integer(value));
	}

	/**
	 * Set the Integer value
	 * 
	 * @param value
	 *            Integer value
	 */
	public void set(Integer value) {
		this.value = value;
	}

	/**
	 * Get the Integer value
	 * 
	 * @return Integer value
	 */
	public Integer get() {
		return value;
	}

	@Override
	public String toString() {
		return get().toString();
	}
}