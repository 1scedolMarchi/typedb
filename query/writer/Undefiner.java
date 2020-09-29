/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.query.writer;

import grabl.tracing.client.GrablTracingThreadStatic.ThreadTrace;
import grakn.core.common.exception.GraknException;
import grakn.core.common.parameters.Context;
import grakn.core.concept.Concepts;
import grakn.core.concept.type.AttributeType;
import grakn.core.concept.type.RelationType;
import grakn.core.concept.type.RoleType;
import grakn.core.concept.type.ThingType;
import grakn.core.concept.type.Type;
import grakn.core.query.pattern.Conjunction;
import grakn.core.query.pattern.constraint.TypeConstraint;
import grakn.core.query.pattern.variable.TypeVariable;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static grabl.tracing.client.GrablTracingThreadStatic.traceOnThread;
import static grakn.core.common.exception.ErrorMessage.TypeRead.TYPE_NOT_FOUND;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.ATTRIBUTE_VALUE_TYPE_UNDEFINED;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.INVALID_UNDEFINE_OWNS_KEY;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.INVALID_UNDEFINE_OWNS_OVERRIDE;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.INVALID_UNDEFINE_PLAYS_OVERRIDE;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.INVALID_UNDEFINE_REGEX;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.INVALID_UNDEFINE_RELATES_OVERRIDE;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.INVALID_UNDEFINE_SUB;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.ROLE_DEFINED_OUTSIDE_OF_RELATION;
import static grakn.core.common.exception.ErrorMessage.TypeWrite.SUPERTYPE_TOO_MANY;

public class Undefiner {

    private static final String TRACE_PREFIX = "undefinewriter.";
    private final Concepts conceptMgr;
    private final Context.Query context;
    private final LinkedList<TypeVariable> variables;
    private final Set<TypeVariable> undefined;

    public Undefiner(Concepts conceptMgr, List<graql.lang.pattern.variable.TypeVariable> variables, Context.Query context) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "constructor")) {
            this.conceptMgr = conceptMgr;
            this.context = context;
            this.variables = new LinkedList<>();
            this.undefined = new HashSet<>();

            Set<TypeVariable> sorted = new HashSet<>();
            Conjunction.fromTypes(variables).patterns().forEach(variable -> {
                if (!sorted.contains(variable)) sort(variable, sorted);
            });
        }
    }

    private void sort(TypeVariable variable, Set<TypeVariable> sorted) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "sort")) {
            if (sorted.contains(variable)) return;
            if (variable.sub().size() == 1) {
                sort(variable.sub().iterator().next().type(), sorted);
            } else if (variable.sub().size() > 1) {
                throw GraknException.of(SUPERTYPE_TOO_MANY.message(variable.label().get().scopedLabel()));
            }
            this.variables.addFirst(variable);
            sorted.add(variable);
        }
    }

    public void execute() {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "write")) {
            variables.forEach(this::undefine);
        }
    }

    private void undefine(TypeVariable variable) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "undefine")) {
            assert variable.label().isPresent();
            TypeConstraint.Label labelConstraint = variable.label().get();

            if (labelConstraint.scope().isPresent() && variable.constraints().size() > 1) {
                throw new GraknException(ROLE_DEFINED_OUTSIDE_OF_RELATION.message(labelConstraint.scopedLabel()));
            } else if (labelConstraint.scope().isPresent()) return; // do nothing
            else if (undefined.contains(variable)) return; // do nothing

            ThingType type = getType(labelConstraint);
            if (type == null) throw new GraknException(TYPE_NOT_FOUND.message(labelConstraint.label()));

            if (!variable.plays().isEmpty()) undefinePlays(type, variable.plays());
            if (!variable.owns().isEmpty()) undefineOwns(type, variable.owns());
            if (!variable.relates().isEmpty()) undefineRelates(type.asRelationType(), variable.relates());

            // TODO: if (variable.then().isPresent()) undefineThen(variable);
            // TODO: if (variable.when().isPresent()) undefineWhen(variable);
            if (variable.regex().isPresent()) undefineRegex(type.asAttributeType().asString(), variable.regex().get());
            if (variable.abstractConstraint().isPresent()) undefineAbstract(type);

            if (variable.sub().size() == 1) undefineSub(type, variable.sub().iterator().next());
            else if (variable.sub().size() > 1) {
                throw GraknException.of(SUPERTYPE_TOO_MANY.message(labelConstraint.label()));
            } else if (variable.valueType().isPresent()) { // variable.sub().size() == 0
                throw new GraknException(ATTRIBUTE_VALUE_TYPE_UNDEFINED.message(
                        variable.valueType().get().valueType().name(),
                        variable.label().get().label()
                ));
            }

            undefined.add(variable);
        }
    }

    private ThingType getType(TypeConstraint.Label label) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "gettype")) {
            Type type;
            if ((type = conceptMgr.getType(label.label())) != null) return type.asThingType();
            else return null;
        }
    }

    private RoleType getRoleType(TypeConstraint.Label label) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "getroletype")) {
            // We always assume that Role Types already exist,
            // defined by their Relation Types ahead of time
            assert label.scope().isPresent();
            Type type;
            RoleType roleType;
            if ((type = conceptMgr.getType(label.scope().get())) == null ||
                    (roleType = type.asRelationType().getRelates(label.label())) == null) {
                throw new GraknException(TYPE_NOT_FOUND.message(label.scopedLabel()));
            }
            return roleType;
        }
    }

    private void undefineSub(ThingType thingType, TypeConstraint.Sub subConstraint) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "undefinesub")) {
            if (thingType instanceof RoleType) {
                throw new GraknException(ROLE_DEFINED_OUTSIDE_OF_RELATION.message(thingType.getLabel()));
            }
            ThingType supertype = getType(subConstraint.type().label().get());
            if (supertype == null) {
                throw new GraknException(TYPE_NOT_FOUND.message(subConstraint.type().label().get()));
            } else if (thingType.getSupertypes().noneMatch(t -> t.equals(supertype))) {
                throw new GraknException(INVALID_UNDEFINE_SUB.message(thingType.getLabel(), supertype.getLabel()));
            }
            thingType.delete();
        }
    }

    private void undefineAbstract(ThingType thingType) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "undefineabstract")) {
            thingType.unsetAbstract();
        }
    }

    private void undefineRegex(AttributeType.String attributeType, TypeConstraint.Regex regexConstraint) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "undefineregex")) {
            if (!attributeType.getRegex().pattern().equals(regexConstraint.regex().pattern())) {
                throw new GraknException(INVALID_UNDEFINE_REGEX.message(attributeType.getLabel(), regexConstraint.regex()));
            }
            attributeType.unsetRegex();
        }
    }

    private void undefineRelates(RelationType relationType, Set<TypeConstraint.Relates> relatesConstraints) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "undefinerelates")) {
            relatesConstraints.forEach(relates -> {
                String roleTypeLabel = relates.role().label().get().label();
                if (roleTypeLabel == null) {
                    throw new GraknException(TYPE_NOT_FOUND.message(relates.role().label().get().label()));
                } else if (relates.overridden().isPresent()) {
                    throw new GraknException(INVALID_UNDEFINE_RELATES_OVERRIDE.message(
                            relates.overridden().get().label().get().label(),
                            relates.role().label().get()
                    ));
                } else {
                    relationType.unsetRelates(roleTypeLabel);
                    undefined.add(relates.role());
                }
            });
        }
    }

    private void undefineOwns(ThingType thingType, Set<TypeConstraint.Owns> ownsConstraints) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "undefineowns")) {
            ownsConstraints.forEach(owns -> {
                AttributeType attributeType = getType(owns.attribute().label().get()).asAttributeType();
                if (attributeType == null && !undefined.contains(owns.attribute())) {
                    throw new GraknException(TYPE_NOT_FOUND.message(owns.attribute().label().get().label()));
                } else if (owns.overridden().isPresent()) {
                    throw new GraknException(INVALID_UNDEFINE_OWNS_OVERRIDE.message(
                            owns.overridden().get().label().get().label(),
                            owns.attribute().label().get()
                    ));
                } else if (owns.isKey()) {
                    throw new GraknException(INVALID_UNDEFINE_OWNS_KEY.message(owns.attribute().label().get()));
                } else if (attributeType != null) {
                    thingType.unsetOwns(attributeType);
                }
            });
        }
    }

    private void undefinePlays(ThingType thingType, Set<TypeConstraint.Plays> playsConstraints) {
        try (ThreadTrace ignored = traceOnThread(TRACE_PREFIX + "undefineplays")) {
            playsConstraints.forEach(plays -> {
                RoleType roleType = getRoleType(plays.role().label().get()).asRoleType();
                if (roleType == null && !undefined.contains(plays.role())) {
                    throw new GraknException(TYPE_NOT_FOUND.message(plays.role().label().get().label()));
                } else if (plays.overridden().isPresent()) {
                    throw new GraknException(INVALID_UNDEFINE_PLAYS_OVERRIDE.message(
                            plays.overridden().get().label().get().label(),
                            plays.role().label().get()
                    ));
                } else if (roleType != null) {
                    thingType.unsetPlays(roleType);
                }
            });
        }
    }
}
