/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.security.authz.privilege;

import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParseException;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.transport.TransportRequest;
import org.elasticsearch.xpack.core.security.action.privilege.ApplicationPrivilegesRequest;
import org.elasticsearch.xpack.core.security.authz.privilege.ConditionalClusterPrivilege.Category;
import org.elasticsearch.xpack.core.security.support.Automatons;
import org.elasticsearch.xpack.core.security.xcontent.XContentUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Static utility class for working with {@link ConditionalClusterPrivilege} instances
 */
public final class ConditionalClusterPrivileges {

    public static final ConditionalClusterPrivilege[] EMPTY_ARRAY = new ConditionalClusterPrivilege[0];

    private ConditionalClusterPrivileges() {
    }

    /**
     * Utility method to read an array of {@link ConditionalClusterPrivilege} objects from a {@link StreamInput}
     */
    public static ConditionalClusterPrivilege[] readArray(StreamInput in) throws IOException {
        return in.readArray(in1 ->
            in1.readNamedWriteable(ConditionalClusterPrivilege.class), ConditionalClusterPrivilege[]::new);
    }

    /**
     * Utility method to write an array of {@link ConditionalClusterPrivilege} objects to a {@link StreamOutput}
     */
    public static void writeArray(StreamOutput out, ConditionalClusterPrivilege[] privileges) throws IOException {
        out.writeArray((out1, value) -> out1.writeNamedWriteable(value), privileges);
    }

    /**
     * Writes a single object value to the {@code builder} that contains each of the provided privileges.
     * The privileges are grouped according to their {@link ConditionalClusterPrivilege#getCategory() categories}
     */
    public static XContentBuilder toXContent(XContentBuilder builder, ToXContent.Params params,
                                             Collection<ConditionalClusterPrivilege> privileges) throws IOException {
        builder.startObject();
        for (Category category : Category.values()) {
            builder.startObject(category.field.getPreferredName());
            for (ConditionalClusterPrivilege privilege : privileges) {
                if (category == privilege.getCategory()) {
                    privilege.toXContent(builder, params);
                }
            }
            builder.endObject();
        }
        return builder.endObject();
    }

    /**
     * Read a list of privileges from the parser. The parser should be positioned at the
     * {@link XContentParser.Token#START_OBJECT} token for the privileges value
     */
    public static List<ConditionalClusterPrivilege> parse(XContentParser parser) throws IOException {
        List<ConditionalClusterPrivilege> privileges = new ArrayList<>();

        expectedToken(parser.currentToken(), parser, XContentParser.Token.START_OBJECT);
        while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
            expectedToken(parser.currentToken(), parser, XContentParser.Token.FIELD_NAME);

            expectFieldName(parser, Category.APPLICATION.field);
            expectedToken(parser.nextToken(), parser, XContentParser.Token.START_OBJECT);
            expectedToken(parser.nextToken(), parser, XContentParser.Token.FIELD_NAME);

            expectFieldName(parser, ManageApplicationPrivileges.Fields.MANAGE);
            privileges.add(ManageApplicationPrivileges.parse(parser));
            expectedToken(parser.nextToken(), parser, XContentParser.Token.END_OBJECT);
        }

        return privileges;
    }

    private static void expectedToken(XContentParser.Token read, XContentParser parser, XContentParser.Token expected) {
        if (read != expected) {
            throw new XContentParseException(parser.getTokenLocation(),
                "failed to parse privilege. expected [" + expected + "] but found [" + read + "] instead");
        }
    }

    private static void expectFieldName(XContentParser parser, ParseField... fields) throws IOException {
        final String fieldName = parser.currentName();
        if (Arrays.stream(fields).anyMatch(pf -> pf.match(fieldName, parser.getDeprecationHandler())) == false) {
            throw new XContentParseException(parser.getTokenLocation(),
                "failed to parse privilege. expected " + (fields.length == 1 ? "field name" : "one of") + " ["
                    + Strings.arrayToCommaDelimitedString(fields) + "] but found [" + fieldName + "] instead");
        }
    }

    /**
     * The {@code ManageApplicationPrivileges} privilege is a {@link ConditionalClusterPrivilege} that grants the
     * ability to execute actions related to the management of application privileges (Get, Put, Delete) for a subset
     * of applications (identified by a wildcard-aware application-name).
     */
    public static class ManageApplicationPrivileges implements ConditionalClusterPrivilege {

        private static final ClusterPrivilege PRIVILEGE = ClusterPrivilege.get(
            Collections.singleton("cluster:admin/xpack/security/privilege/*")
        );
        public static final String WRITEABLE_NAME = "manage-application-privileges";

        private final Set<String> applicationNames;
        private final Predicate<String> applicationPredicate;
        private final Predicate<TransportRequest> requestPredicate;

        public ManageApplicationPrivileges(Set<String> applicationNames) {
            this.applicationNames = Collections.unmodifiableSet(applicationNames);
            this.applicationPredicate = Automatons.predicate(applicationNames);
            this.requestPredicate = request -> {
                if (request instanceof ApplicationPrivilegesRequest) {
                    final ApplicationPrivilegesRequest privRequest = (ApplicationPrivilegesRequest) request;
                    final Collection<String> requestApplicationNames = privRequest.getApplicationNames();
                    return requestApplicationNames.isEmpty() ? this.applicationNames.contains("*")
                        : requestApplicationNames.stream().allMatch(application -> applicationPredicate.test(application));
                }
                return false;
            };
        }

        @Override
        public Category getCategory() {
            return Category.APPLICATION;
        }

        @Override
        public ClusterPrivilege getPrivilege() {
            return PRIVILEGE;
        }

        @Override
        public Predicate<TransportRequest> getRequestPredicate() {
            return this.requestPredicate;
        }

        public Collection<String> getApplicationNames() {
            return Collections.unmodifiableCollection(this.applicationNames);
        }

        @Override
        public String getWriteableName() {
            return WRITEABLE_NAME;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeCollection(this.applicationNames, StreamOutput::writeString);
        }

        public static ManageApplicationPrivileges createFrom(StreamInput in) throws IOException {
            final Set<String> applications = in.readSet(StreamInput::readString);
            return new ManageApplicationPrivileges(applications);
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            return builder.field(Fields.MANAGE.getPreferredName(),
                Collections.singletonMap(Fields.APPLICATIONS.getPreferredName(), applicationNames)
            );
        }

        public static ManageApplicationPrivileges parse(XContentParser parser) throws IOException {
            expectedToken(parser.currentToken(), parser, XContentParser.Token.FIELD_NAME);
            expectFieldName(parser, Fields.MANAGE);
            expectedToken(parser.nextToken(), parser, XContentParser.Token.START_OBJECT);
            expectedToken(parser.nextToken(), parser, XContentParser.Token.FIELD_NAME);
            expectFieldName(parser, Fields.APPLICATIONS);
            expectedToken(parser.nextToken(), parser, XContentParser.Token.START_ARRAY);
            final String[] applications = XContentUtils.readStringArray(parser, false);
            expectedToken(parser.nextToken(), parser, XContentParser.Token.END_OBJECT);
            return new ManageApplicationPrivileges(new LinkedHashSet<>(Arrays.asList(applications)));
        }

        @Override
        public String toString() {
            return "{" + getCategory() + ":" + Fields.MANAGE.getPreferredName() + ":" + Fields.APPLICATIONS.getPreferredName() + "="
                + Strings.collectionToDelimitedString(applicationNames, ",") + "}";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            final ManageApplicationPrivileges that = (ManageApplicationPrivileges) o;
            return this.applicationNames.equals(that.applicationNames);
        }

        @Override
        public int hashCode() {
            return applicationNames.hashCode();
        }

        private interface Fields {
            ParseField MANAGE = new ParseField("manage");
            ParseField APPLICATIONS = new ParseField("applications");
        }
    }
}
