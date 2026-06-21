package org.jahia.community.clamav.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLDescription("ClamAV queries")
public class ClamavQueryExtension {

    private ClamavQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("clamav")
    @GraphQLDescription("ClamAV query namespace")
    public static ClamavQuery clamav() {
        return new ClamavQuery();
    }
}
