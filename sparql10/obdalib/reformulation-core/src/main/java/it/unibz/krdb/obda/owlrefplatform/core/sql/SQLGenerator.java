package it.unibz.krdb.obda.owlrefplatform.core.sql;

import it.unibz.krdb.obda.model.AlgebraOperatorPredicate;
import it.unibz.krdb.obda.model.Atom;
import it.unibz.krdb.obda.model.BooleanOperationPredicate;
import it.unibz.krdb.obda.model.CQIE;
import it.unibz.krdb.obda.model.Constant;
import it.unibz.krdb.obda.model.DataTypePredicate;
import it.unibz.krdb.obda.model.DatalogProgram;
import it.unibz.krdb.obda.model.Function;
import it.unibz.krdb.obda.model.NewLiteral;
import it.unibz.krdb.obda.model.OBDAException;
import it.unibz.krdb.obda.model.OBDAQueryModifiers.OrderCondition;
import it.unibz.krdb.obda.model.Predicate;
import it.unibz.krdb.obda.model.URIConstant;
import it.unibz.krdb.obda.model.ValueConstant;
import it.unibz.krdb.obda.model.Variable;
import it.unibz.krdb.obda.model.impl.OBDAVocabulary;
import it.unibz.krdb.obda.owlrefplatform.core.queryevaluation.JDBCUtility;
import it.unibz.krdb.obda.owlrefplatform.core.queryevaluation.SQLDialectAdapter;
import it.unibz.krdb.obda.owlrefplatform.core.srcquerygeneration.SQLQueryGenerator;
import it.unibz.krdb.sql.DBMetadata;
import it.unibz.krdb.sql.DataDefinition;
import it.unibz.krdb.sql.TableDefinition;
import it.unibz.krdb.sql.ViewDefinition;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.Vector;

import org.slf4j.LoggerFactory;

public class SQLGenerator implements SQLQueryGenerator {

	private static final long serialVersionUID = 7477161929752147045L;

	/**
	 * Operator symbols
	 */
	private static final String EQ_OPERATOR = "%s = %s";
	private static final String NEQ_OPERATOR = "%s <> %s";
	private static final String GT_OPERATOR = "%s > %s";
	private static final String GTE_OPERATOR = "%s >= %s";
	private static final String LT_OPERATOR = "%s < %s";
	private static final String LTE_OPERATOR = "%s <= %s";
	private static final String AND_OPERATOR = "%s AND %s";
	private static final String OR_OPERATOR = "%s OR %s";
	private static final String NOT_OPERATOR = "NOT %s";
	private static final String IS_NULL_OPERATOR = "%s IS NULL";
	private static final String IS_NOT_NULL_OPERATOR = "%s IS NOT NULL";

	/**
	 * Formatting template
	 */
	private static final String VIEW_NAME = "QVIEW%s";

	private final DBMetadata metadata;
	private final JDBCUtility jdbcutil;
	private final SQLDialectAdapter sqladapter;

	private static final org.slf4j.Logger log = LoggerFactory
			.getLogger(SQLGenerator.class);

	public SQLGenerator(DBMetadata metadata, JDBCUtility jdbcutil,
			SQLDialectAdapter sqladapter) {
		this.metadata = metadata;
		this.jdbcutil = jdbcutil;
		this.sqladapter = sqladapter;
	}

	/***
	 * Generates and SQL query ready to be executed by Quest. Each query is a
	 * SELECT FROM WHERE query. To know more about each of these see the inner
	 * method descriptions.
	 */
	@Override
	public String generateSourceQuery(DatalogProgram query,
			List<String> signature) throws OBDAException {
		String indent = "   ";
		if (query.getQueryModifiers().hasModifiers()) {
			final String outerViewName = "SUB_QVIEW";
			String subquery = generateQuery(query, signature, indent);

			String modifier = "";
			List<OrderCondition> conditions = query.getQueryModifiers()
					.getSortConditions();
			if (!conditions.isEmpty()) {
				modifier += sqladapter.sqlOrderBy(conditions, outerViewName)
						+ "\n";
			}

			long limit = query.getQueryModifiers().getLimit();
			long offset = query.getQueryModifiers().getOffset();
			if (limit != -1 || offset != -1) {
				modifier += sqladapter.sqlSlice(limit, offset) + "\n";
			}

			String sql = "SELECT *\n";
			sql += "FROM (\n";
			sql += subquery + "\n";
			sql += ") " + outerViewName + "\n";
			sql += modifier;

			return sql;

		} else {
			return generateQuery(query, signature, "");
		}
	}

	/***
	 * Main method. Generates the full query, taking into account
	 * limit/offset/order by.
	 * 
	 * @param query
	 * @param signature
	 * @param indent
	 * @return
	 * @throws OBDAException
	 */
	private String generateQuery(DatalogProgram query, List<String> signature,
			String indent) throws OBDAException {

		boolean distinct = query.getQueryModifiers().isDistinct();
		int numberOfQueries = query.getRules().size();

		List<String> queriesStrings = new LinkedList<String>();
		/* Main loop, constructing the SPJ query for each CQ */
		for (CQIE cq : query.getRules()) {

			Predicate headPredicate = cq.getHead().getFunctionSymbol();
			if (!headPredicate.getName().toString().equals("ans1")) {
				// not a target query, skip it.
				continue;
			}

			QueryAliasIndex index = new QueryAliasIndex(cq);

			boolean innerdistincts = false;
			if (distinct && numberOfQueries == 1) {
				innerdistincts = true;
			}

			String FROM = getFROM(cq, index);
			String WHERE = getWHERE(cq, index);
			String SELECT = getSelectClause(signature, cq, index,
					innerdistincts);

			String querystr = SELECT + FROM + WHERE;
			queriesStrings.add(querystr);
		}

		Iterator<String> queryStringIterator = queriesStrings.iterator();
		StringBuffer result = new StringBuffer();
		if (queryStringIterator.hasNext()) {
			result.append(queryStringIterator.next());
		}

		String UNION = null;
		if (distinct)
			UNION = "UNION";
		else
			UNION = "UNION ALL";

		while (queryStringIterator.hasNext()) {
			result.append("\n");
			result.append(UNION);
			result.append("\n\n");
			result.append(queryStringIterator.next());
		}

		return result.toString();
	}

	/***
	 * Returns a string with boolean conditions formed with the boolean atoms
	 * found in the atoms list.
	 */
	private LinkedHashSet<String> getBooleanConditionsString(
			List<Function> atoms, QueryAliasIndex index) {

		LinkedHashSet<String> conditions = new LinkedHashSet<String>();

		for (int atomidx = 0; atomidx < atoms.size(); atomidx++) {
			NewLiteral innerAtom = atoms.get(atomidx);
			Function innerAtomAsFunction = (Function) innerAtom;

			if (innerAtomAsFunction.isDataFunction())
				continue;

			/* This is a boolean atom */
			String condition = getSQLCondition(innerAtomAsFunction, index);
			conditions.add(condition);
		}

		return conditions;
	}
	
	/***
	 * Returns the SQL for an atom representing an SQL condition (booleans)
	 * 
	 * @param atom
	 * @param index
	 * @return
	 */
	private String getSQLCondition(Function atom, QueryAliasIndex index) {
		Predicate functionSymbol = atom.getFunctionSymbol();
		if (isUnary(atom)) {
			// For unary boolean operators, e.g., NOT, IS NULL, IS NOT NULL.
			NewLiteral term = atom.getTerms().get(0);
			String expressionFormat = getBooleanOperatorString(functionSymbol);
			String column = getSQLString(term, index, false);
			return String.format(expressionFormat, column);

		} else if (isBinary(atom)) {
			// For binary boolean operators, e.g., AND, OR, EQ, GT, LT, etc. _
			// LangMatches
			NewLiteral left = atom.getTerms().get(0);
			NewLiteral right = atom.getTerms().get(1);
			String expressionFormat = getBooleanOperatorString(functionSymbol);

			String leftOp = getSQLString(left, index, true);
			String rightOp = getSQLString(right, index, true);
			return String.format("(" + expressionFormat + ")", leftOp, rightOp);

		}
		// SQL conditions can only be unary or binary!
		throw new RuntimeException(
				"No support for n-ary boolean condition predicate: "
						+ atom.getPredicate());
	}

	/***
	 * Returns the table definition for these atoms. By default, a list of atoms
	 * represents JOIN or LEFT JOIN of all the atoms, left to right. All boolean
	 * atoms in the list are considered conditions in the ON clause of the JOIN.
	 * 
	 * <p>
	 * If the list is a LeftJoin, then it can only have 2 data atoms, and it HAS
	 * to have 2 data atoms.
	 * 
	 * <p>
	 * If process boolean operators is enabled, all boolean conditions will be
	 * added to the ON clause of the first JOIN.
	 * 
	 * @param atoms
	 * @param index
	 * @param isTopLevel
	 *            indicates if the list of atoms is actually the main body of
	 *            the conjunctive query. If it is, no JOIN is generated, but a
	 *            cross product with WHERE clause. Moreover, the isLeftJoin
	 *            argument will be ignored.
	 * 
	 * @return
	 */
	private String getTableDefinitions(List<Function> inneratoms,
			QueryAliasIndex index, boolean isTopLevel, boolean isLeftJoin) {

		/*
		 * We now collect the view definitions for each data atom each
		 * condition, and each each nested Join/LeftJoin
		 */

		List<String> tableDefinitions = new LinkedList<String>();

		for (int atomidx = 0; atomidx < inneratoms.size(); atomidx++) {
			NewLiteral innerAtom = inneratoms.get(atomidx);
			Function innerAtomAsFunction = (Function) innerAtom;

			if (!innerAtomAsFunction.isDataFunction())
				continue;

			String definition = getTableDefinition(innerAtomAsFunction, index);
			tableDefinitions.add(definition);

		}

		/*
		 * Now we generate the table definition, this will be either a comma
		 * separated list for TOP level (FROM clause) or a Join/LeftJoin
		 * (possibly nested if there are more than 2 table definitions in the
		 * current list) in case this method was called recursively.
		 */

		StringBuffer tableDefinitionsString = new StringBuffer();

		int size = tableDefinitions.size();
		if (isTopLevel) {

			if (size == 0)
				throw new RuntimeException("No table definitions");

			Iterator<String> tableDefinitionsIterator = tableDefinitions
					.iterator();
			tableDefinitionsString.append("   ");
			tableDefinitionsString.append(tableDefinitionsIterator.next());
			while (tableDefinitionsIterator.hasNext()) {
				tableDefinitionsString.append(",\n");
				tableDefinitionsString.append("   ");
				tableDefinitionsString.append(tableDefinitionsIterator.next());
			}

		} else {

			/*
			 * This is actually a Join or LeftJoin, so we form the JOINs/LEFT
			 * JOINs and the ON clauses
			 */
			String JOIN_KEYWORD = null;
			if (isLeftJoin) {
				JOIN_KEYWORD = "LEFT JOIN";
			} else {
				JOIN_KEYWORD = "JOIN";
			}
			String JOIN = "(%s " + JOIN_KEYWORD + " %s)";

			if (size < 2)
				throw new RuntimeException(
						"JOIN generation requires at least 2 tables");

			/*
			 * To form the JOIN we will cycle through each data definition,
			 * nesting the JOINs as we go. The conditions in the ON clause will
			 * go on the TOP level only.
			 */
			String currentJoin = String.format(JOIN,
					tableDefinitions.get(size - 2),
					tableDefinitions.get(size - 1));
			tableDefinitions.remove(size - 1);
			tableDefinitions.remove(size - 1);

			int currentSize = tableDefinitions.size();
			while (currentSize > 0) {
				currentJoin = String.format(JOIN,
						tableDefinitions.get(currentSize - 1), currentJoin);
				tableDefinitions.remove(currentSize - 1);
			}
			tableDefinitions.add(currentJoin);

			/*
			 * If there are ON conditions we add them now. We need to remove the
			 * last parenthesis ')' and replace it with ' ON %s)' where %s are
			 * all the conditions
			 */
			String conditions = getConditionsString(inneratoms, index);

			if (conditions.length() > 0) {
				tableDefinitionsString
						.deleteCharAt(tableDefinitions.size() - 1);
				String ON_CLAUSE = String.format(" ON %s)", conditions);
				tableDefinitionsString.append(ON_CLAUSE);
			}
		}

		return tableDefinitionsString.toString();
	}

	/***
	 * Returns the table definition for the given atom. If the atom is a simple
	 * table or view, then it returns the value as defined by the
	 * QueryAliasIndex. If the atom is a Join or Left Join, it will call
	 * getTableDefinitions on the nested term list.
	 * 
	 * @param atom
	 * @param index
	 * @return
	 */
	private String getTableDefinition(Function atom, QueryAliasIndex index) {

		Predicate predicate = atom.getPredicate();

		if (predicate instanceof BooleanOperationPredicate) {
			/*
			 * These don't participate in the FROM clause
			 */
			return "";
		} else if (predicate instanceof AlgebraOperatorPredicate) {

			List<Function> innerTerms = new LinkedList<Function>();
			for (NewLiteral innerTerm : atom.getTerms())
				innerTerms.add((Function) innerTerm);
			if (predicate == OBDAVocabulary.SPARQL_JOIN) {
				return getTableDefinitions(innerTerms, index, false, false);
			} else if (predicate == OBDAVocabulary.SPARQL_LEFTJOIN) {
				return getTableDefinitions(innerTerms, index, false, true);
			}
		}

		/*
		 * This is a data atom
		 */
		String def = index.getViewDefinition(atom);
		return def;
	}

	private String getFROM(CQIE query, QueryAliasIndex index) {
		List<Function> atoms = new LinkedList<Function>();
		for (Atom atom : query.getBody())
			atoms.add((Function) atom);

		String tableDefinitions = getTableDefinitions(atoms, index, true, false);
		return "\n FROM \n" + tableDefinitions;

	}

	/***
	 * Generates all the conditions on the given atoms, e.g., shared variables
	 * and boolean conditions. This string can then be used to form a WHERE or
	 * an ON clause.
	 * 
	 * <p>
	 * The method assumes that no variable in this list (or nested ones) referes
	 * to an upper level one.
	 * 
	 * @param atoms
	 * @param index
	 * @return
	 */
	private String getConditionsString(List<Function> atoms,
			QueryAliasIndex index) {

		LinkedHashSet<String> equalityConditions = getConditionsSharedVariablesAndConstants(
				atoms, index);
		LinkedHashSet<String> booleanConditions = getBooleanConditionsString(
				atoms, index);

		LinkedHashSet<String> conditions = new LinkedHashSet<String>();
		conditions.addAll(equalityConditions);
		conditions.addAll(booleanConditions);

		/*
		 * Collecting all the conditions in a single string for the ON or WHERE
		 * clause
		 */
		StringBuffer conditionsString = new StringBuffer();
		Iterator<String> conditionsIterator = conditions.iterator();
		if (conditionsIterator.hasNext())
			conditionsString.append("   ");
		conditionsString.append(conditionsIterator.next());
		conditionsString.append("\n");
		while (conditionsIterator.hasNext()) {
			conditionsString.append("   AND ");
			conditionsString.append(conditionsIterator.next());
			conditionsString.append("\n");
		}
		return conditionsString.toString();
	}

	/***
	 * Returns a list of equality conditions that reflect the semantics of the
	 * shared variables in the list of atoms.
	 * <p>
	 * The method assumes that no variables are shared across deeper levels of
	 * nesting (through Join or LeftJoin atoms), it will not call itself
	 * recursively. Nor across upper levels.
	 * 
	 * <p>
	 * When generating equalities recursively, we will also generate a minimal
	 * number of equalities. E.g., if we have A(x), Join(R(x,y), Join(R(y,
	 * x),B(x))
	 * 
	 */
	private LinkedHashSet<String> getConditionsSharedVariablesAndConstants(
			List<Function> atoms, QueryAliasIndex index) {
		LinkedHashSet<String> equalities = new LinkedHashSet<String>();

		Set<Variable> currentLevelVariables = new LinkedHashSet<Variable>();
		for (Function atom : atoms) {
			if (!atom.isDataFunction())
				continue;
			currentLevelVariables.addAll(atom.getReferencedVariables());

		}

		/*
		 * For each variable we collect all the columns that shold be equated
		 * (due to repeated positions of the variable). then we form atoms of
		 * the form "COL1 = COL2"
		 */

		for (Variable var : currentLevelVariables) {
			Set<String> references = index.getColumnReferences(var);
			if (references.size() < 2) {
				// No need for equality
				continue;
			}
			Iterator<String> referenceIterator = references.iterator();
			String leftColumnReference = referenceIterator.next();
			while (referenceIterator.hasNext()) {
				String rightColumnReference = referenceIterator.next();
				String equality = String.format("(%s = %s)",
						leftColumnReference, rightColumnReference);
				equalities.add(equality);
				leftColumnReference = rightColumnReference;
			}
		}

		for (Function atom : atoms) {
			if (!atom.isDataFunction())
				continue;
			for (int idx = 0; idx < atom.getArity(); idx++) {
				NewLiteral l = atom.getTerm(idx);
				if (l instanceof Constant) {
					String value = getSQLString(l, index, false);
					String columnReference = index
							.getColumnReference(atom, idx);
					equalities.add(String.format("(%s = %s)", columnReference,
							value));
				}
			}

		}

		return equalities;

	}

	private String getWHERE(CQIE query, QueryAliasIndex index) {
		List<Function> atoms = new LinkedList<Function>();
		for (Atom atom : query.getBody())
			atoms.add((Function) atom);

		String conditions = getConditionsString(atoms, index);
		if (conditions.length() == 0)
			return "";

		return "\n WHERE \n" + conditions;
	}

	/**
	 * produces the select clause of the sql query for the given CQIE
	 * 
	 * @param q
	 *            the query
	 * @return the sql select clause
	 */
	private String getSelectClause(List<String> signature, CQIE query,
			QueryAliasIndex index, boolean distinct) throws OBDAException {

		/*
		 * If the head has size 0 this is a boolean query.
		 */

		List<NewLiteral> headterms = query.getHead().getTerms();
		StringBuilder sb = new StringBuilder();

		sb.append("SELECT ");
		if (distinct)
			sb.append("DISTINCT ");

		if (headterms.size() == 0) {
			sb.append("true as x");
			return sb.toString();
		}

		String typeStr = "%s AS \"%sQuestType\", ";

		Iterator<NewLiteral> hit = headterms.iterator();
		int hpos = 0;
		while (hit.hasNext()) {
			sb.append("\n   ");
			NewLiteral ht = hit.next();
			if (!((ht instanceof Function) || (ht instanceof Constant))) {
				throw new IllegalArgumentException(
						"Unexpected error. Contact the authors. Message: Imposible to generate SELECT clause. Found non-functional term \""
								+ ht.toString() + "\"");
			}

			if (ht instanceof Function) {
				Vector<String> vex = new Vector<String>();
				Function ov = (Function) ht;
				Predicate function = ov.getFunctionSymbol();
				String functionString = function.toString();

				/*
				 * Adding the ColType column to the projection (used in the
				 * result set to know the type of constant)
				 */
				if (functionString.equals(OBDAVocabulary.XSD_BOOLEAN.getName()
						.toString())) {
					sb.append(String.format(typeStr, 9, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.XSD_DATETIME
						.getName().toString())) {
					sb.append(String.format(typeStr, 8, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.XSD_DECIMAL
						.getName().toString())) {
					sb.append(String.format(typeStr, 5, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.XSD_DOUBLE
						.getName().toString())) {
					sb.append(String.format(typeStr, 6, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.XSD_INTEGER
						.getName().toString())) {
					sb.append(String.format(typeStr, 4, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.XSD_STRING
						.getName().toString())) {
					sb.append(String.format(typeStr, 7, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.RDFS_LITERAL
						.getName().toString())) {
					sb.append(String.format(typeStr, 3, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.QUEST_URI)) {
					sb.append(String.format(typeStr, 1, signature.get(hpos)));
				} else if (functionString.equals(OBDAVocabulary.QUEST_BNODE)) {
					sb.append(String.format(typeStr, 2, signature.get(hpos)));
				}

				/*
				 * Adding the column(s) with the actual value(s)
				 */
				if (function instanceof DataTypePredicate) {
					/*
					 * Case where we have a typing function in the head (this is
					 * the case for all literal columns
					 */
					String langStr = "%s AS \"%sLang\", ";
					if (function == OBDAVocabulary.RDFS_LITERAL) {

						/*
						 * Case for rdf:literal s with a language, we need to
						 * select 2 terms from ".., rdf:literal(?x,"en"),
						 * 
						 * and signature "name" * we will generate a select with
						 * the projection of 2 columns
						 * 
						 * , 'en' as nameqlang, view.colforx as name,
						 */

						/*
						 * first we add the column for language, we have two
						 * cases, where the language is already in the function
						 * as a constant, e.g,. "en" or where the language is a
						 * variable that must be obtained from a column in the
						 * query
						 */
						String lang = "''";
						if (ov.getTerms().size() > 1) {
							NewLiteral langTerm = ov.getTerms().get(1);
							if (langTerm instanceof ValueConstant) {
								lang = jdbcutil
										.getSQLLexicalForm((ValueConstant) langTerm);
							} else {
								lang = getSQLString(langTerm, index, false);
							}
						}
						sb.append(String.format(langStr, lang,
								signature.get(hpos)));

						NewLiteral term = ov.getTerms().get(0);
						String termStr = null;
						if (term instanceof ValueConstant) {
							termStr = jdbcutil
									.getSQLLexicalForm((ValueConstant) term);
						} else {
							termStr = getSQLString(term, index, false);
						}
						sb.append(termStr);

					} else {
						// The default value for language column: NULL
						sb.append(String.format(langStr, "''",
								signature.get(hpos)));

						// The column name
						NewLiteral term = ov.getTerms().get(0);
						if (term instanceof Variable) {
							Variable v = (Variable) term;
							String column = getSQLString(v, index, false);
							sb.append(column);
						} else if (term instanceof ValueConstant) {
							ValueConstant c = (ValueConstant) term;
							sb.append(jdbcutil.getSQLLexicalForm(c));
						}
					}
				} else if (functionString.equals(OBDAVocabulary.QUEST_URI)) {
					/***
					 * New template based URI building functions
					 */

					String langStr = "%s AS \"%sLang\", ";

					sb.append(String.format(langStr, "NULL",
							signature.get(hpos)));

					String result = "";

					result = getSQLStringForURIFunction(ov, index);

					sb.append(result);

				} else {
					throw new IllegalArgumentException(
							"Error generating SQL query. Contact the developers. Found an invalid function during translation: "
									+ ov.toString());
				}
			} else if (ht instanceof URIConstant) {

				sb.append(String.format(typeStr, 1, signature.get(hpos)));

				String langStr = "%s AS \"%sLang\", ";
				sb.append(String.format(langStr, "NULL", signature.get(hpos)));

				URIConstant uc = (URIConstant) ht;
				sb.append(jdbcutil.getSQLLexicalForm(uc.getURI().toString()));

			} else if (ht instanceof ValueConstant) {
				if (ht != OBDAVocabulary.NULL) {
					throw new RuntimeException(
							"Cannot generate SELECT for term: " + ht.toString());
				}
				sb.append(String.format(typeStr, 0, signature.get(hpos)));
				//
				String langStr = "%s AS \"%sLang\", ";
				sb.append(String.format(langStr, "NULL", signature.get(hpos)));
				// The default value for language column: NULL
				sb.append("NULL");
			} else {
				throw new RuntimeException("Cannot generate SELECT for term: "
						+ ht.toString());
			}
			sb.append(" AS ");
			sb.append(sqladapter.sqlQuote(signature.get(hpos)));

			if (hit.hasNext()) {
				sb.append(", ");
			}
			hpos++;
		}
		return sb.toString();
	}

	/***
	 * Returns the SQL that builds a URI String out of an atom of the form
	 * uri("htttp:...", x, y,...)
	 * 
	 * @param ov
	 * @param index
	 * @return
	 */
	public String getSQLStringForURIFunction(Function ov, QueryAliasIndex index) {

		/*
		 * The first inner term determines the form of the result
		 */
		NewLiteral t = ov.getTerms().get(0);

		if (t instanceof ValueConstant) {
			/*
			 * The function is actually a template. The first parameter is a
			 * string of the form http://.../.../ with place holders of the form
			 * {}. The rest are variables or constants that should be put in
			 * place of the palce holders. We need to tokenize and form the
			 * CONCAT
			 */
			ValueConstant c = (ValueConstant) t;
			StringTokenizer tokenizer = new StringTokenizer(c.toString(), "{}");
			String functionString = jdbcutil.getSQLLexicalForm(tokenizer
					.nextToken());
			List<String> vex = new LinkedList<String>();
			int termIndex = 1;
			do {
				NewLiteral currentTerm = ov.getTerms().get(termIndex);
				vex.add(getSQLString(currentTerm, index, false));
				if (tokenizer.hasMoreTokens()) {
					vex.add(jdbcutil.getSQLLexicalForm(tokenizer.nextToken()));
				}
				termIndex += 1;
			} while (tokenizer.hasMoreElements()
					|| termIndex < ov.getTerms().size());
			String[] params = new String[vex.size() + 1];
			int i = 0;
			params[i] = functionString;
			i += 1;
			for (String param : vex) {
				params[i] = param;
				i += 1;
			}
			return sqladapter.strconcat(params);
		} else if (t instanceof Variable) {
			/*
			 * The function is of the form uri(x), we need to simply return the
			 * value of X
			 */

			return getSQLString(((Variable) t), index, false);
		} else if (t instanceof URIConstant) {
			URIConstant uc = (URIConstant) t;
			/*
			 * The function is of the form uri("http://some.uri/"), i.e., a
			 * concrete URI, we return the string representing that URI.
			 */
			return jdbcutil.getSQLLexicalForm(uc.getURI().toString());
		}

		/*
		 * Unsupported case
		 */
		throw new IllegalArgumentException(
				"Error, cannot generate URI constructor clause for a term. Contact the authors. Term: "
						+ ov.toString());

	}



	/**
	 * Determines if it is a unary function.
	 */
	private boolean isUnary(Function fun) {
		return (fun.getArity() == 1) ? true : false;
	}

	/**
	 * Determines if it is a binary function.
	 */
	private boolean isBinary(Function fun) {
		return (fun.getArity() == 2) ? true : false;
	}

	/***
	 * Generates the SQL string that forms or retrieves the given term. The
	 * function takes as input either: a constant (value or URI), a variable, or
	 * a Function (i.e., uri(), eq(..), ISNULL(..), etc)).
	 * <p>
	 * If the input is a constant, it will return the SQL that generates the
	 * string representing that constant.
	 * <p>
	 * If its a variable, it returns the column references to the position where
	 * the variable first appears.
	 * <p>
	 * If its a function uri(..) it returns the SQL string concatenation that
	 * builds the result of uri(...)
	 * <p>
	 * If its a boolean comparison, it returns the corresponding SQL comparison.
	 * 
	 * @param term
	 * @param index
	 * @param useBrackets
	 * @return
	 */
	public String getSQLString(NewLiteral term, QueryAliasIndex index,
			boolean useBrackets) {
		if (term instanceof ValueConstant) {
			ValueConstant ct = (ValueConstant) term;
			return jdbcutil.getSQLLexicalForm(ct);
		} else if (term instanceof URIConstant) {
			URIConstant uc = (URIConstant) term;
			return jdbcutil.getSQLLexicalForm(uc.toString());

		} else if (term instanceof Variable) {
			Variable var = (Variable) term;
			LinkedHashSet<String> posList = index.getColumnReferences(var);
			if (posList.size() == 0) {
				throw new RuntimeException(
						"Unbound variable found in WHERE clause: " + term);
			}
			return posList.iterator().next();
		}

		/* If its not constant, or variable its a function */

		Function function = (Function) term;
		Predicate functionSymbol = function.getFunctionSymbol();
		NewLiteral term1 = function.getTerms().get(0);

		if (functionSymbol instanceof DataTypePredicate) {

			/* atoms of the form integer(x) */
			return getSQLString(term1, index, false);

		} else if (functionSymbol instanceof BooleanOperationPredicate) {

			/* atoms of the form EQ(x,y) */

			String expressionFormat = getBooleanOperatorString(functionSymbol);
			if (isUnary(function)) {

				// for unary functions, e.g., NOT, IS NULL, IS NOT NULL
				String op = getSQLString(term1, index, true);
				return String.format(expressionFormat, op);
			} else if (isBinary(function)) {

				// for binary functions, e.g., AND, OR, EQ, NEQ, GT, etc.
				String leftOp = getSQLString(term1, index, true);
				NewLiteral term2 = function.getTerms().get(1);
				String rightOp = getSQLString(term2, index, true);
				String result = String
						.format(expressionFormat, leftOp, rightOp);
				if (useBrackets) {
					return String.format("(%s)", result);
				}
				return result;
			} else {
				throw new RuntimeException(
						"Cannot translate boolean function: " + functionSymbol);
			}
		}

		/*
		 * The atom must be of the form uri("...", x, y)
		 */

		String functionName = function.getFunctionSymbol().toString();
		if (functionName.equals(OBDAVocabulary.QUEST_URI)) {
			return getSQLStringForURIFunction(function, index);

		} else {
			throw new RuntimeException("Unexpected function in the query: "
					+ functionSymbol);

		}

	}

	/***
	 * Returns the SQL string for the boolean operator, including placeholders
	 * for the terms to be used, e.g., %s = %s, %s IS NULL, etc.
	 * 
	 * @param functionSymbol
	 * @return
	 */
	private String getBooleanOperatorString(Predicate functionSymbol) {
		String operator = null;
		if (functionSymbol.equals(OBDAVocabulary.EQ)) {
			operator = EQ_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.NEQ)) {
			operator = NEQ_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.GT)) {
			operator = GT_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.GTE)) {
			operator = GTE_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.LT)) {
			operator = LT_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.LTE)) {
			operator = LTE_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.AND)) {
			operator = AND_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.OR)) {
			operator = OR_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.NOT)) {
			operator = NOT_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.IS_NULL)) {
			operator = IS_NULL_OPERATOR;
		} else if (functionSymbol.equals(OBDAVocabulary.IS_NOT_NULL)) {
			operator = IS_NOT_NULL_OPERATOR;
		} else {
			throw new RuntimeException("Unknown boolean operator: "
					+ functionSymbol);
		}
		return operator;
	}

	/***
	 * Utility class to resolve "database" atoms to view definitions ready to be
	 * used in a FROM clause, and variables, to column references defined over
	 * the existing view definitons of a query.
	 * 
	 * 
	 * @author mariano
	 * 
	 */
	public class QueryAliasIndex {

		Map<Function, String> viewNames = new HashMap<Function, String>();
		Map<Function, String> tableNames = new HashMap<Function, String>();
		Map<Function, DataDefinition> dataDefinitions = new HashMap<Function, DataDefinition>();
		Map<Variable, LinkedHashSet<String>> columnReferences = new HashMap<Variable, LinkedHashSet<String>>();

		int dataTableCount = 0;
		boolean isEmpty = false;

		public QueryAliasIndex(CQIE query) {
			List<Atom> body = query.getBody();
			generateViews(body);
		}

		private void generateViews(List<Atom> atoms) {
			for (Atom atom : atoms) {
				/*
				 * Thios wil call recursively if necessary
				 */
				generateViewsIndexVariables(atom);
			}
		}

		/***
		 * We assiciate each atom to a view definition. This will be
		 * <p>
		 * "tablename" as "viewX" or
		 * <p>
		 * (some nested sql view) as "viewX"
		 * 
		 * <p>
		 * View definitions are only done for data atoms. Join/LeftJoin and
		 * boolean atoms are not associated to view definitions.
		 * 
		 * @param atom
		 */
		private void generateViewsIndexVariables(Function atom) {
			if (atom.getFunctionSymbol() instanceof BooleanOperationPredicate) {
				return;
			} else if (atom.getFunctionSymbol() instanceof AlgebraOperatorPredicate) {
				List<NewLiteral> lit = atom.getTerms();
				for (NewLiteral subatom : lit) {
					if (subatom instanceof Function) {
						generateViewsIndexVariables((Function) subatom);
					}

				}
			}

			Predicate tablePredicate = atom.getFunctionSymbol();
			String tableName = tablePredicate.toString();
			DataDefinition def = metadata.getDefinition(tableName);
			if (def == null) {
				/*
				 * There is no definition for this atom, its not a database
				 * predicate, the query is empty.
				 */
				isEmpty = true;
				return;
			}

			dataTableCount += 1;
			viewNames.put(atom, String.format(VIEW_NAME, dataTableCount));
			tableNames.put(atom, tableName);
			dataDefinitions.put(atom, def);

			indexVariables(atom);

		}

		private void indexVariables(Function atom) {
			DataDefinition def = dataDefinitions.get(atom);
			String viewName = viewNames.get(atom);
			for (int index = 0; index < atom.getTerms().size(); index++) {

				NewLiteral term = atom.getTerms().get(index);
				if (!(term instanceof Variable))
					continue;

				LinkedHashSet<String> references = columnReferences.get(term);
				if (references == null) {
					references = new LinkedHashSet<String>();
					columnReferences.put((Variable) term, references);
				}
				String columnName = def.getAttributeName(index + 1);
				String reference = sqladapter.sqlQualifiedColumn(viewName,
						columnName);
				references.add(reference);
			}

		}

		/***
		 * Returns all the column aliases that correspond to this variable,
		 * across all the DATA atoms in the query (not algebra operators or
		 * boolean conditions.
		 * 
		 * @param var
		 *            The variable we want the referenced columns.
		 */
		public LinkedHashSet<String> getColumnReferences(Variable var) {

			return columnReferences.get(var);
		}

		/***
		 * Generates the view definition, i.e., "tablename viewname"
		 * 
		 * @param atom
		 * @return
		 */
		public String getViewDefinition(Function atom) {
			DataDefinition def = dataDefinitions.get(atom);
			if (def instanceof TableDefinition) {
				return sqladapter.sqlTableName(tableNames.get(atom),
						viewNames.get(atom));
			} else if (def instanceof ViewDefinition) {
				return String.format("(%s) %s",
						((ViewDefinition) def).getStatement(),
						viewNames.get(atom));
			}
			throw new RuntimeException(
					"Impossible to get data definition for: " + atom
							+ ", type: " + def);
		}

		public String getView(Function atom) {
			return viewNames.get(atom);
		}

		public String getColumnReference(Function atom, int column) {
			String viewName = getView(atom);
			DataDefinition def = dataDefinitions.get(atom);
			String columnname = def.getAttributeName(column + 1);
			return sqladapter.sqlQualifiedColumn(viewName, columnname);
		}

	}
}