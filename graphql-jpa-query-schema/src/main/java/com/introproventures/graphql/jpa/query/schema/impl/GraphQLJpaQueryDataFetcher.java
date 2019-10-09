/*
 * Copyright 2017 IntroPro Ventures Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.introproventures.graphql.jpa.query.schema.impl;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.From;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.persistence.metamodel.EntityType;

import graphql.language.Argument;
import graphql.language.BooleanValue;
import graphql.language.Field;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.GraphQLObjectType;

/**
 * JPA Query DataFetcher implementation that fetches entities with page and where criteria expressions   
 * 
 * @author Igor Dianov
 *
 */
class GraphQLJpaQueryDataFetcher extends QraphQLJpaBaseDataFetcher {

    private boolean defaultDistinct = true;
	
    protected static final String HIBERNATE_QUERY_PASS_DISTINCT_THROUGH = "hibernate.query.passDistinctThrough";
    protected static final String ORG_HIBERNATE_CACHEABLE = "org.hibernate.cacheable";
    protected static final String ORG_HIBERNATE_FETCH_SIZE = "org.hibernate.fetchSize";
    protected static final String ORG_HIBERNATE_READ_ONLY = "org.hibernate.readOnly";
    protected static final String JAVAX_PERSISTENCE_FETCHGRAPH = "javax.persistence.fetchgraph";

    private GraphQLJpaQueryDataFetcher(EntityManager entityManager, EntityType<?> entityType, boolean toManyDefaultOptional) {
        super(entityManager, entityType, toManyDefaultOptional);
    }

    public GraphQLJpaQueryDataFetcher(EntityManager entityManager, 
                                      EntityType<?> entityType, 
                                      boolean defaultDistinct,
                                      boolean toManyDefaultOptional) {
        super(entityManager, entityType, toManyDefaultOptional);
        this.defaultDistinct = defaultDistinct;
    }

    public boolean isDefaultDistinct() {
        return defaultDistinct;
    }

    public void setDefaultDistinct(boolean defaultDistinct) {
        this.defaultDistinct = defaultDistinct;
    }

    @Override
    public Object get(DataFetchingEnvironment environment) {
        Field field = environment.getField();
        Map<String, Object> result = new LinkedHashMap<>();

        // See which fields we're requesting
        Optional<Field> pagesSelection = getSelectionField(field, GraphQLJpaSchemaBuilder.PAGE_PAGES_PARAM_NAME);
        Optional<Field> totalSelection = getSelectionField(field, GraphQLJpaSchemaBuilder.PAGE_TOTAL_PARAM_NAME);
        Optional<Field> recordsSelection = getSelectionField(field, GraphQLJpaSchemaBuilder.QUERY_SELECT_PARAM_NAME);

        Optional<Argument> pageArgument = getPageArgument(field);
        Page page = extractPageArgument(environment, pageArgument);
        field = removeArgument(field, pageArgument);

        Argument distinctArg = extractArgument(environment, field, GraphQLJpaSchemaBuilder.SELECT_DISTINCT_PARAM_NAME, new BooleanValue(defaultDistinct));
        
        boolean isDistinct = ((BooleanValue) distinctArg.getValue()).isValue();
        
        DataFetchingEnvironment queryEnvironment = environment;
        Field queryField = field;
        
        if (recordsSelection.isPresent()) {
            // Override query environment  
            String fieldName = recordsSelection.get().getName();
            
            queryEnvironment = 
                Optional.of(getFieldDef(environment.getGraphQLSchema(), (GraphQLObjectType)environment.getParentType(), field))
                    .map(it -> (GraphQLObjectType) it.getType())
                    .map(it -> it.getFieldDefinition(GraphQLJpaSchemaBuilder.QUERY_SELECT_PARAM_NAME))
                    .map(it -> DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment)
                                                             .fieldType(it.getType())
                                                             .build()
                    ).orElse(environment);
            
            queryField = new Field(fieldName, field.getArguments(), recordsSelection.get().getSelectionSet());
            
            TypedQuery<?> query = getQuery(queryEnvironment, queryField, isDistinct);
            
            // Let's apply page only if present
            if(pageArgument.isPresent()) {
            	query
            		.setMaxResults(page.size)
                	.setFirstResult((page.page - 1) * page.size);
            }
            
            // Let' try reduce overhead and disable all caching
            query.setHint(ORG_HIBERNATE_READ_ONLY, true);
            query.setHint(ORG_HIBERNATE_FETCH_SIZE, 1000);
            query.setHint(ORG_HIBERNATE_CACHEABLE, false);
            
            // Let's not pass distinct if enabled to have better performance
            if(isDistinct) {
                query.setHint(HIBERNATE_QUERY_PASS_DISTINCT_THROUGH, false);
            }

            // Let's execute query 
            List<?> resultList = query.getResultList(); 
                            
            // Let's remove any duplicate references for root entities 
            if(isDistinct) {
                resultList = resultList.stream()
                                       .distinct()
                                       .collect(Collectors.toList());
            }
                
            result.put(GraphQLJpaSchemaBuilder.QUERY_SELECT_PARAM_NAME, resultList);
        }
        
        if (totalSelection.isPresent() || pagesSelection.isPresent()) {
            final DataFetchingEnvironment countQueryEnvironment = queryEnvironment;
            final Field countQueryField = queryField;
            
            final Long total = recordsSelection
                    .map(contentField -> getCountQuery(countQueryEnvironment, countQueryField).getSingleResult())
                    // if no "content" was selected an empty Field can be used
                    .orElseGet(() -> getCountQuery(environment, new Field("count")).getSingleResult());

            result.put(GraphQLJpaSchemaBuilder.PAGE_TOTAL_PARAM_NAME, total);
            result.put(GraphQLJpaSchemaBuilder.PAGE_PAGES_PARAM_NAME, ((Double) Math.ceil(total / (double) page.size)).longValue());
        }

        return result;
    }

    
    @Override
    protected Predicate getPredicate(CriteriaBuilder cb, Root<?> root, From<?,?> path, DataFetchingEnvironment environment, Argument argument) {
        if(isLogicalArgument(argument) || isDistinctArgument(argument))
            return null;
        
        if(isWhereArgument(argument)) 
            return getWherePredicate(cb, root, path, argumentEnvironment(environment, argument), argument);
        
        return super.getPredicate(cb, root, path, environment, argument);
    }

    
    private TypedQuery<Long> getCountQuery(DataFetchingEnvironment environment, Field field) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<?> root = query.from(entityType);

        DataFetchingEnvironment queryEnvironment = DataFetchingEnvironmentImpl.newDataFetchingEnvironment(environment)
                                                                                 .root(query)
                                                                                 .build();
        
        query.select(cb.count(root));
        
        List<Predicate> predicates = field.getArguments().stream()
            .map(it -> getPredicate(cb, root, null, queryEnvironment, it))
            .filter(it -> it != null)
            .collect(Collectors.toList());
        
        query.where(predicates.toArray(new Predicate[predicates.size()]));

        return entityManager.createQuery(query);
    }

    
    private Optional<Argument> getPageArgument(Field field) {
	    return field.getArguments()
		    .stream()
		    .filter(it -> GraphQLJpaSchemaBuilder.PAGE_PARAM_NAME.equals(it.getName()))
		    .findFirst();
	}

    
    private Page extractPageArgument(DataFetchingEnvironment environment, Optional<Argument> paginationRequest) {

        if (paginationRequest.isPresent()) {

            Map<String, Integer> pagex = environment.getArgument(GraphQLJpaSchemaBuilder.PAGE_PARAM_NAME);
            
            Integer start = pagex.get(GraphQLJpaSchemaBuilder.PAGE_START_PARAM_NAME);
            Integer limit = pagex.get(GraphQLJpaSchemaBuilder.PAGE_LIMIT_PARAM_NAME);

            return new Page(start, limit);
        }

        return new Page(1, Integer.MAX_VALUE);
    }

    private Field removeArgument(Field field, Optional<Argument> argument) {

      if (!argument.isPresent()) {
        return field;
      }

      List<Argument> newArguments = field.getArguments().stream()
          .filter(a -> !a.equals(argument.get())).collect(Collectors.toList());

      return field.transform(builder -> builder.arguments(newArguments));

    }

    private Boolean isWhereArgument(Argument argument) {
        return GraphQLJpaSchemaBuilder.QUERY_WHERE_PARAM_NAME.equals(argument.getName());
        
    }

    private Boolean isLogicalArgument(Argument argument) {
        return GraphQLJpaSchemaBuilder.QUERY_LOGICAL_PARAM_NAME.equals(argument.getName());
    }

    private Boolean isDistinctArgument(Argument argument) {
        return GraphQLJpaSchemaBuilder.SELECT_DISTINCT_PARAM_NAME.equals(argument.getName());
    }
    
    private static final class Page {
        public Integer page;
        public Integer size;

        public Page(Integer page, Integer size) {
            this.page = page;
            this.size = size;
        }
    }

}
