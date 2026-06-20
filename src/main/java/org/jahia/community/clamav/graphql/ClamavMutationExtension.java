package org.jahia.community.clamav.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLDescription("ClamAV mutations")
public class ClamavMutationExtension {

    private ClamavMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("clamav")
    @GraphQLDescription("ClamAV mutation namespace")
    public static ClamavMutation clamav() {
        return new ClamavMutation();
    }
}
