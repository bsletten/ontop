package inf.unibz.it.ucq.renderer;

import inf.unibz.it.obda.api.controller.APIController;
import inf.unibz.it.ucq.domain.ConjunctiveQuery;
import inf.unibz.it.ucq.domain.ConstantTerm;
import inf.unibz.it.ucq.domain.QueryAtom;
import inf.unibz.it.ucq.domain.QueryTerm;
import inf.unibz.it.ucq.domain.UnionOfConjunctiveQueries;
import inf.unibz.it.utils.codec.ObjectToTextCodec;

import java.util.Iterator;
import java.util.List;
/**
 * The UCQDatalogStringRenderer should be used to transform a 
 * UnionOfConjunctiveQueries object into a String or vice versa.
 * This renderer should be used instead of the toString function. 
 * 
 * @author Manfred Gerstgrasser
 *
 */

public class UCQDatalogStringRenderer extends ObjectToTextCodec<UnionOfConjunctiveQueries> {

	/**
	 * The constructor. Creates a new instance of the UCQDatalogStringRenderer
	 * 
	 * @param apic the current api controller
	 */
	public UCQDatalogStringRenderer(APIController apic) {
		super(apic);
		// TODO Auto-generated constructor stub
	}

	/**
	 * Please do no use this function
	 */
	@Override
	@Deprecated
	public UnionOfConjunctiveQueries decode(String input) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Represents the given UnionOfConjunctiveQueries objects as a String
	 */
	@Override
	public String encode(UnionOfConjunctiveQueries input) {
		
		StringBuffer str = new StringBuffer();
		
		Iterator<ConjunctiveQuery> it = input.getQueries().iterator();
		while(it.hasNext()){
			ConjunctiveQuery cq = it.next();
			String head = renderCQHead(cq.getHeadTerms());
			String body = renderCQBody(cq.getAtoms());
			str.append(head);
			str.append(" :- ");
			str.append(body);
			str.append(".\n");
		}
		
		return str.toString();
	}

	/**
	 * private method which creates the String of the head of the ucq
	 * @param head the head of the ucq object
	 * @return the head as String representation
	 */
	private String renderCQHead(List<QueryTerm> head){
		
		StringBuffer str = new StringBuffer();
		str.append("q(");
		StringBuffer str2 = new StringBuffer();
		Iterator<QueryTerm> it = head.iterator();
		while(it.hasNext()){
			if(str2.length()>0){
				str2.append(",");
			}
			str2.append(it.next().getVariableName());
		}
		str.append(str2.toString());
		str.append(")");
		return str.toString();
	}
	
	/**
	 * private method which creates String of the body of the ucq
	 * @param body the body of the ucq object
	 * @return the body as String representation
	 */
	private String renderCQBody(List<QueryAtom> body){
		
		StringBuffer str = new StringBuffer();
		Iterator<QueryAtom> it = body.iterator();
		while(it.hasNext()){
			if(str.length() >0){
				str.append(",");
			}
			str.append(renderQueryAtom(it.next()));
		}
		return str.toString();
	}
	
	/**
	 * private method to create the String representation of a query atom.
	 * @param atom a query atom
	 * @return the String representation of the given atom.
	 */
	
	private String renderQueryAtom(QueryAtom atom){
		
		StringBuffer str = new StringBuffer();
		String aux = apic.getEntityNameRenderer().getPredicateName(atom);
		str.append(aux);
		str.append("(");
		StringBuffer str2 = new StringBuffer();
		Iterator<QueryTerm> it =atom.getTerms().iterator();
		while(it.hasNext()){
			QueryTerm t = it.next();
			if(str2.length()>0){
				str2.append(",");
			}
			if(t instanceof ConstantTerm){
				str2.append("'");
				str2.append(t.getVariableName());
				str2.append("'");
			}else{
				str2.append(t.getVariableName());
			}
		}
		str.append(str2.toString());
		str.append(")");
		return str.toString();
	}
	
	/**
	 *Can be used to create the String representation of a query atom.
	 * @param atom a query atom
	 * @return the String representation of the given atom.
	 */
	public String encodeAtom(QueryAtom atom){
		return renderQueryAtom(atom);
	}
}