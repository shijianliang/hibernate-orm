/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or http://www.gnu.org/licenses/lgpl-2.1.html
 */
package org.hibernate.query.sqm.tree.select;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.hibernate.query.FetchClauseType;
import org.hibernate.query.SemanticException;
import org.hibernate.query.SetOperator;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.query.criteria.JpaExpression;
import org.hibernate.query.criteria.JpaOrder;
import org.hibernate.query.criteria.JpaQueryGroup;
import org.hibernate.query.sqm.NodeBuilder;
import org.hibernate.query.sqm.SemanticQueryWalker;
import org.hibernate.query.sqm.tree.from.SqmAttributeJoin;
import org.hibernate.query.sqm.tree.from.SqmFrom;
import org.hibernate.query.sqm.tree.from.SqmJoin;
import org.hibernate.type.descriptor.java.JavaType;

/**
 * A grouped list of queries connected through a certain set operator.
 *
 * @author Christian Beikov
 */
public class SqmQueryGroup<T> extends SqmQueryPart<T> implements JpaQueryGroup<T> {

	private final List<SqmQueryPart<T>> queryParts;
	private SetOperator setOperator;

	public SqmQueryGroup(SqmQueryPart<T> queryPart) {
		this( queryPart.nodeBuilder(), null, CollectionHelper.listOf( queryPart ) );
	}

	public SqmQueryGroup(
			NodeBuilder nodeBuilder,
			SetOperator setOperator,
			List<SqmQueryPart<T>> queryParts) {
		super( nodeBuilder );
		this.setOperator = setOperator;
		this.queryParts = queryParts;
	}

	public List<SqmQueryPart<T>> queryParts() {
		return queryParts;
	}

	@Override
	public SqmQuerySpec<T> getFirstQuerySpec() {
		return queryParts.get( 0 ).getFirstQuerySpec();
	}

	@Override
	public SqmQuerySpec<T> getLastQuerySpec() {
		return queryParts.get( queryParts.size() - 1 ).getLastQuerySpec();
	}

	@Override
	public boolean isSimpleQueryPart() {
		return setOperator == null && queryParts.size() == 1 && queryParts.get( 0 ).isSimpleQueryPart();
	}

	@Override
	public <X> X accept(SemanticQueryWalker<X> walker) {
		return walker.visitQueryGroup( this );
	}

	@Override
	public List<SqmQueryPart<T>> getQueryParts() {
		return Collections.unmodifiableList( queryParts );
	}

	@Override
	public SetOperator getSetOperator() {
		return setOperator;
	}

	@Override
	public void setSetOperator(SetOperator setOperator) {
		if ( setOperator == null ) {
			throw new IllegalArgumentException();
		}
		this.setOperator = setOperator;
	}


	@Override
	public SqmQueryGroup<T> setSortSpecifications(List<? extends JpaOrder> sortSpecifications) {
		return (SqmQueryGroup<T>) super.setSortSpecifications( sortSpecifications );
	}

	@Override
	public SqmQueryGroup<T> setOffset(JpaExpression<?> offset) {
		return (SqmQueryGroup<T>) super.setOffset( offset );
	}

	@Override
	public SqmQueryGroup<T> setFetch(JpaExpression<?> fetch) {
		return (SqmQueryGroup<T>) super.setFetch( fetch );
	}

	@Override
	public SqmQueryGroup<T> setFetch(JpaExpression<?> fetch, FetchClauseType fetchClauseType) {
		return (SqmQueryGroup<T>) super.setFetch( fetch, fetchClauseType );
	}

	@Override
	public void validateQueryGroupFetchStructure() {
		validateQueryGroupFetchStructure( getFirstQuerySpec() );
	}

	private void validateQueryGroupFetchStructure(SqmQuerySpec<?> firstQuerySpec) {
		final List<SqmSelection<?>> firstSelections = firstQuerySpec.getSelectClause().getSelections();
		final int firstSelectionSize = firstSelections.size();
		for ( int i = 0; i < queryParts.size(); i++ ) {
			final SqmQueryPart<T> queryPart = queryParts.get( i );
			if ( queryPart instanceof SqmQueryGroup<?> ) {
				( (SqmQueryGroup<Object>) queryPart ).validateQueryGroupFetchStructure( firstQuerySpec );
			}
			else {
				final SqmQuerySpec<?> querySpec = (SqmQuerySpec<?>) queryPart;
				final List<SqmSelection<?>> selections = querySpec.getSelectClause().getSelections();
				if ( firstSelectionSize != selections.size() ) {
					throw new SemanticException( "All query parts in a query group must have the same arity!" );
				}
				for ( int j = 0; j < firstSelectionSize; j++ ) {
					final SqmSelection<?> firstSqmSelection = firstSelections.get( j );
					final JavaType<?> firstJavaTypeDescriptor = firstSqmSelection.getNodeJavaTypeDescriptor();
					if ( firstJavaTypeDescriptor != selections.get( j ).getNodeJavaTypeDescriptor() ) {
						throw new SemanticException(
								"Select items of the same index must have the same java type across all query parts!"
						);
					}
					if ( firstSqmSelection.getSelectableNode() instanceof SqmFrom<?, ?> ) {
						final SqmFrom<?, ?> firstFrom = (SqmFrom<?, ?>) firstSqmSelection.getSelectableNode();
						final SqmFrom<?, ?> from = (SqmFrom<?, ?>) selections.get( j ).getSelectableNode();
						validateFetchesMatch( firstFrom, from );
					}
				}
			}
		}
	}

	private void validateFetchesMatch(SqmFrom<?, ?> firstFrom, SqmFrom<?, ?> from) {
		final Iterator<? extends SqmJoin<?, ?>> firstJoinIter = firstFrom.getSqmJoins().iterator();
		final Iterator<? extends SqmJoin<?, ?>> joinIter = from.getSqmJoins().iterator();
		while ( firstJoinIter.hasNext() ) {
			final SqmJoin<?, ?> firstSqmJoin = firstJoinIter.next();
			if ( firstSqmJoin instanceof SqmAttributeJoin<?, ?> ) {
				final SqmAttributeJoin<?, ?> firstAttrJoin = (SqmAttributeJoin<?, ?>) firstSqmJoin;
				if ( firstAttrJoin.isFetched() ) {
					SqmAttributeJoin<?, ?> matchingAttrJoin = null;
					while ( joinIter.hasNext() ) {
						final SqmJoin<?, ?> sqmJoin = joinIter.next();
						if ( sqmJoin instanceof SqmAttributeJoin<?, ?> ) {
							final SqmAttributeJoin<?, ?> attrJoin = (SqmAttributeJoin<?, ?>) sqmJoin;
							if ( attrJoin.isFetched() ) {
								matchingAttrJoin = attrJoin;
								break;
							}
						}
					}
					if ( matchingAttrJoin == null || firstAttrJoin.getModel() != matchingAttrJoin.getModel() ) {
						throw new SemanticException(
								"All query parts in a query group must have the same join fetches in the same order!"
						);
					}
					validateFetchesMatch( firstAttrJoin, matchingAttrJoin );
				}
			}
		}
		// At this point, the other iterator should only contain non-fetch joins
		while ( joinIter.hasNext() ) {
			final SqmJoin<?, ?> sqmJoin = joinIter.next();
			if ( sqmJoin instanceof SqmAttributeJoin<?, ?> ) {
				final SqmAttributeJoin<?, ?> attrJoin = (SqmAttributeJoin<?, ?>) sqmJoin;
				if ( attrJoin.isFetched() ) {
					throw new SemanticException(
							"All query parts in a query group must have the same join fetches in the same order!"
					);
				}
			}
		}
	}

	@Override
	public void appendHqlString(StringBuilder sb) {
		appendQueryPart( queryParts.get( 0 ), sb );
		for ( int i = 1; i < queryParts.size(); i++ ) {
			sb.append( ' ' );
			sb.append( setOperator.sqlString() );
			sb.append( ' ' );
			appendQueryPart( queryParts.get( i ), sb );
		}
		super.appendHqlString( sb );
	}

	private static void appendQueryPart(SqmQueryPart<?> queryPart, StringBuilder sb) {
		final boolean needsParenthesis = !queryPart.isSimpleQueryPart();
		if ( needsParenthesis ) {
			sb.append( '(' );
		}
		queryPart.appendHqlString( sb );
		if ( needsParenthesis ) {
			sb.append( ')' );
		}
	}
}