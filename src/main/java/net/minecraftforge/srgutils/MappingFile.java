/*
 * SRG Utils
 * Copyright (c) 2021
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.srgutils;

import javax.annotation.Nullable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.minecraftforge.srgutils.InternalUtils.Element.*;
import static net.minecraftforge.srgutils.InternalUtils.writeMeta;

class MappingFile implements IMappingFile {
    static final Pattern DESC = Pattern.compile("L(?<cls>[^;]+);");
    private final Map<String, Package> packages = new HashMap<>();
    private final Collection<Package> packagesView = Collections.unmodifiableCollection(packages.values());
    private final Map<String, Cls> classes = new HashMap<>();
    private final Collection<Cls> classesView = Collections.unmodifiableCollection(classes.values());
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    MappingFile() {
    }

    MappingFile(NamedMappingFile source, int from, int to) {
        source.getPackages().forEach(pkg -> addPackage(pkg.getName(from), pkg.getName(to), pkg.meta));
        source.getClasses().forEach(cls -> {
            Cls c = addClass(cls.getName(from), cls.getName(to), cls.meta);
            cls.getFields().forEach(fld -> c.addField(fld.getName(from), fld.getName(to), fld.getDescriptor(from), fld.meta));
            cls.getMethods().forEach(mtd -> {
                Cls.Method m = c.addMethod(mtd.getName(from), mtd.getDescriptor(from), mtd.getName(to), mtd.meta);
                mtd.getParameters().forEach(par -> m.addParameter(par.getIndex(), par.getName(from), par.getName(to), par.meta));
            });
        });
    }

    private static <K, V> V retPut(Map<K, V> map, K key, V value) {
        map.put(key, value);
        return value;
    }

    @Override
    public Collection<Package> getPackages() {
        return packagesView;
    }

    @Override
    public @Nullable Package getPackage(String original) {
        return packages.get(original);
    }

    private Package addPackage(String original, String mapped, Map<String, String> metadata) {
        return packages.put(original, new Package(this, original, mapped, metadata));
    }

    @Override
    public Collection<Cls> getClasses() {
        return classesView;
    }

    @Override
    public @Nullable Cls getClass(String original) {
        return classes.get(original);
    }

    @Override
    public @Nullable IClass getMappedClass(String mapped) {
        return classesView.stream().filter(cls -> cls.getMapped().equals(mapped)).findFirst().orElse(null);
    }

    private Cls addClass(String original, String mapped, Map<String, String> metadata) {
        return retPut(classes, original, new Cls(this, original, mapped, metadata));
    }

    @Override
    public String remapPackage(String pkg) {
        //TODO: Package bulk moves? Issue: moving default package will move EVERYTHING, it's what its meant to do but we shouldn't.
        Package ipkg = packages.get(pkg);
        return ipkg == null ? pkg : ipkg.getMapped();
    }

    @Override
    public String remapClass(String cls) {
        String ret = cache.get(cls);
        if (ret == null) {
            Cls _cls = classes.get(cls);
            if (_cls == null) {
                int idx = cls.lastIndexOf('$');
                if (idx != -1)
                    ret = remapClass(cls.substring(0, idx)) + '$' + cls.substring(idx + 1);
                else
                    ret = cls;
            } else
                ret = _cls.getMapped();
            //TODO: Package bulk moves? Issue: moving default package will move EVERYTHING, it's what its meant to do but we shouldn't.
            cache.put(cls, ret);
        }
        return ret;
    }

    @Override
    public String remapDescriptor(String desc) {
        Matcher matcher = DESC.matcher(desc);
        StringBuffer buf = new StringBuffer();
        while (matcher.find())
            matcher.appendReplacement(buf, Matcher.quoteReplacement("L" + remapClass(matcher.group("cls")) + ";"));
        matcher.appendTail(buf);
        return buf.toString();
    }

    @Override
    public void write(Path path, Format format, boolean reversed) throws IOException {
        List<String> lines = new ArrayList<>();
        Comparator<INode> sort = reversed ? Comparator.comparing(INode::getMapped) : Comparator.comparing(INode::getOriginal);

        getPackages().stream().sorted(sort).forEachOrdered(pkg -> {
            lines.add(pkg.write(format, reversed));
            writeMeta(format, lines, PACKAGE, pkg.getMetadata());
        });
        getClasses().stream().sorted(sort).forEachOrdered(cls -> {
            lines.add(cls.write(format, reversed));
            writeMeta(format, lines, CLASS, cls.getMetadata());

            cls.getFields().stream().sorted(sort).forEachOrdered(fld -> {
                lines.add(fld.write(format, reversed));
                writeMeta(format, lines, FIELD, fld.getMetadata());
            });

            cls.getMethods().stream().sorted(sort).forEachOrdered(mtd -> {
                lines.add(mtd.write(format, reversed));
                writeMeta(format, lines, METHOD, mtd.getMetadata());

                mtd.getParameters().stream().sorted(Comparator.comparingInt(Cls.Method.Parameter::getIndex)).forEachOrdered(par -> {
                    lines.add(par.write(format, reversed));
                    writeMeta(format, lines, PARAMETER, par.getMetadata());
                });
            });
        });

        lines.removeIf(Objects::isNull);

        if (!format.isOrdered()) {
            Comparator<String> linesort = (format == Format.SRG || format == Format.XSRG) ? InternalUtils::compareLines : Comparator.naturalOrder();
            lines.sort(linesort);
        }

        if (format == Format.TINY1) {
            lines.add(0, "v1\tleft\tright");
        } else if (format == Format.TINY) {
            lines.add(0, "tiny\t2\t0\tleft\tright");
        } else if (format == Format.TSRG2) {
            lines.add(0, "tsrg2 left right");
        }

        Files.createDirectories(path.getParent());
        try (BufferedWriter writer = Files.newBufferedWriter(path)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
        }
    }

    @Override
    public MappingFile reverse() {
        MappingFile ret = new MappingFile();
        getPackages().forEach(pkg -> ret.addPackage(pkg.getMapped(), pkg.getOriginal(), pkg.getMetadata()));
        getClasses().forEach(cls -> {
            Cls c = ret.addClass(cls.getMapped(), cls.getOriginal(), cls.getMetadata());
            cls.getFields().forEach(fld -> c.addField(fld.getMapped(), fld.getOriginal(), fld.getMappedDescriptor(), fld.getMetadata()));
            cls.getMethods().forEach(mtd -> {
                Cls.Method m = c.addMethod(mtd.getMapped(), mtd.getMappedDescriptor(), mtd.getOriginal(), mtd.getMetadata());
                mtd.getParameters().forEach(par -> m.addParameter(par.getIndex(), par.getMapped(), par.getOriginal(), par.getMetadata()));
            });
        });
        return ret;
    }

    @Override
    public MappingFile rename(IRenamer renamer) {
        MappingFile ret = new MappingFile();
        getPackages().forEach(pkg -> ret.addPackage(pkg.getOriginal(), renamer.rename(pkg), pkg.getMetadata()));
        getClasses().forEach(cls -> {
            Cls c = ret.addClass(cls.getOriginal(), renamer.rename(cls), cls.getMetadata());
            cls.getFields().forEach(fld -> c.addField(fld.getOriginal(), renamer.rename(fld), fld.getDescriptor(), fld.getMetadata()));
            cls.getMethods().forEach(mtd -> {
                Cls.Method m = c.addMethod(mtd.getOriginal(), mtd.getDescriptor(), renamer.rename(mtd), mtd.getMetadata());
                mtd.getParameters().forEach(par -> m.addParameter(par.getIndex(), par.getOriginal(), renamer.rename(par), par.getMetadata()));
            });
        });
        return ret;
    }

    @Override
    public MappingFile chain(final IMappingFile link) {
        return rename(new IRenamer() {
            public String rename(IPackage value) {
                return link.remapPackage(value.getMapped());
            }

            public String rename(IClass value) {
                return link.remapClass(value.getMapped());
            }

            public String rename(IField value) {
                IClass cls = link.getClass(value.getParent().getMapped());
                return cls == null ? value.getMapped() : cls.remapField(value.getMapped());
            }

            public String rename(IMethod value) {
                IClass cls = link.getClass(value.getParent().getMapped());
                return cls == null ? value.getMapped() : cls.remapMethod(value.getMapped(), value.getMappedDescriptor());
            }

            public String rename(IParameter value) {
                IMethod mtd = value.getParent();
                IClass cls = link.getClass(mtd.getParent().getMapped());
                mtd = cls == null ? null : cls.getMethod(mtd.getMapped(), mtd.getMappedDescriptor());
                return mtd == null ? value.getMapped() : mtd.remapParameter(value.getIndex(), value.getMapped());
            }
        });
    }

    abstract static class Node implements INode {
        private final String original;
        private final String mapped;
        private final Map<String, String> metadata;

        Node(String original, String mapped, Map<String, String> metadata) {
            this.original = original;
            this.mapped = mapped;
            this.metadata = Collections.unmodifiableMap(metadata);
        }

        @Override
        public String getOriginal() {
            return original;
        }

        @Override
        public String getMapped() {
            return mapped;
        }

        @Override
        public Map<String, String> getMetadata() {
            return metadata;
        }
    }

    static class Package extends Node implements IPackage {
        private final MappingFile parent;

        Package(MappingFile parent, String original, String mapped, Map<String, String> metadata) {
            super(original, mapped, metadata);
            this.parent = parent;
        }

        @Override
        public @Nullable String write(Format format, boolean reversed) {
            String sorig = getOriginal().isEmpty() ? "." : getOriginal();
            String smap = getMapped().isEmpty() ? "." : getMapped();

            if (reversed) {
                String tmp = sorig;
                sorig = smap;
                smap = tmp;
            }

            switch (format) {
                case SRG:
                case XSRG:
                    return "PK: " + sorig + ' ' + smap;
                case CSRG:
                case TSRG:
                case TSRG2:
                    return getOriginal() + "/ " + getMapped() + '/';
                case PG:
                case TINY1:
                    return null;
                default:
                    throw new UnsupportedOperationException("Unknown format: " + format);
            }
        }

        @Override
        public String toString() {
            return write(Format.SRG, false);
        }

        @Override
        public IMappingFile getParent() {
            return parent;
        }
    }

    static class Cls extends Node implements IClass {
        private final Map<String, Field> fields = new HashMap<>();
        private final Collection<Field> fieldsView = Collections.unmodifiableCollection(fields.values());
        private final Map<String, Method> methods = new HashMap<>();
        private final Collection<Method> methodsView = Collections.unmodifiableCollection(methods.values());
        private final MappingFile parent;

        Cls(MappingFile parent, String original, String mapped, Map<String, String> metadata) {
            super(original, mapped, metadata);
            this.parent = parent;
        }

        @Override
        public @Nullable String write(Format format, boolean reversed) {
            String oName = !reversed ? getOriginal() : getMapped();
            String mName = !reversed ? getMapped() : getOriginal();
            switch (format) {
                case SRG:
                case XSRG:
                    return "CL: " + oName + ' ' + mName;
                case CSRG:
                case TSRG:
                case TSRG2:
                    return oName + ' ' + mName;
                case PG:
                    return oName.replace('/', '.') + " -> " + mName.replace('/', '.') + ':';
                case TINY1:
                    return "CLASS\t" + oName + '\t' + mName;
                case TINY:
                    return "c\t" + oName + '\t' + mName;
                default:
                    throw new UnsupportedOperationException("Unknown format: " + format);
            }
        }

        @Override
        public Collection<Field> getFields() {
            return fieldsView;
        }

        @Override
        public @Nullable IField getField(String name) {
            return fields.get(name);
        }

        @Override
        public @Nullable IField getMappedField(String name) {
            return fieldsView.stream().filter(field -> field.getMapped().equals(name)).findFirst().orElse(null);
        }

        @Override
        public String remapField(String field) {
            Field fld = fields.get(field);
            return fld == null ? field : fld.getMapped();
        }

        private Field addField(String original, String mapped, String desc, Map<String, String> metadata) {
            return retPut(fields, original, new Field(this, original, mapped, desc, metadata));
        }

        @Override
        public Collection<Method> getMethods() {
            return methodsView;
        }

        @Override
        public @Nullable IMethod getMethod(String name, String desc) {
            return methods.get(name + desc);
        }

        @Override
        public @Nullable IMethod getMappedMethod(String name, String desc) {
            return methodsView.stream()
                    .filter(method -> method.getMapped().equals(name) && method.getMappedDescriptor().equals(desc))
                    .findFirst()
                    .orElse(null);
        }

        private Method addMethod(String original, String desc, String mapped, Map<String, String> metadata) {
            return retPut(methods, original + desc, new Method(this, original, desc, mapped, metadata));
        }

        @Override
        public String remapMethod(String name, String desc) {
            Method mtd = methods.get(name + desc);
            return mtd == null ? name : mtd.getMapped();
        }

        @Override
        public String toString() {
            return write(Format.SRG, false);
        }

        @Override
        public IMappingFile getParent() {
            return parent;
        }

        static class Field extends Node implements IField {
            private final String desc;
            private final Cls parent;

            Field(Cls parent, String original, String mapped, String desc, Map<String, String> metadata) {
                super(original, mapped, metadata);
                this.desc = desc;
                this.parent = parent;
            }

            @Override
            public String getDescriptor() {
                return desc;
            }

            @Override
            public String getMappedDescriptor() {
                return desc == null ? null : getParent().getParent().remapDescriptor(desc);
            }

            @Override
            public @Nullable String write(Format format, boolean reversed) {
                if (format != Format.TSRG2 && format.hasFieldTypes() && desc == null)
                    throw new IllegalStateException("Can not write " + format.name() + " format, field is missing descriptor");

                String oOwner = !reversed ? getParent().getOriginal() : getParent().getMapped();
                String mOwner = !reversed ? getParent().getMapped() : getParent().getOriginal();
                String oName = !reversed ? getOriginal() : getMapped();
                String mName = !reversed ? getMapped() : getOriginal();
                String oDesc = !reversed ? getDescriptor() : getMappedDescriptor();
                String mDesc = !reversed ? getMappedDescriptor() : getDescriptor();

                switch (format) {
                    case SRG:
                        return "FD: " + oOwner + '/' + oName + ' ' + mOwner + '/' + mName + (oDesc == null ? "" : " # " + oDesc + " " + mDesc);
                    case XSRG:
                        return "FD: " + oOwner + '/' + oName + (oDesc == null ? "" : ' ' + oDesc) + ' ' + mOwner + '/' + mName + (mDesc == null ? "" : ' ' + mDesc);
                    case CSRG:
                        return oOwner + ' ' + oName + ' ' + mName;
                    case TSRG:
                        return '\t' + oName + ' ' + mName;
                    case TSRG2:
                        return '\t' + oName + (oDesc == null ? "" : ' ' + oDesc) + ' ' + mName;
                    case PG:
                        return "    " + InternalUtils.toSource(Objects.requireNonNull(oDesc)) + ' ' + oName + " -> " + mName;
                    case TINY1:
                        return "FIELD\t" + oOwner + '\t' + oDesc + '\t' + oName + '\t' + mName;
                    case TINY:
                        return "\tf\t" + oDesc + '\t' + oName + '\t' + mName;
                    default:
                        throw new UnsupportedOperationException("Unknown format: " + format);
                }
            }

            @Override
            public String toString() {
                return write(Format.SRG, false);
            }

            @Override
            public Cls getParent() {
                return parent;
            }
        }

        static class Method extends Node implements IMethod {
            private final String desc;
            private final Map<Integer, Parameter> params = new HashMap<>();
            private final Collection<Parameter> paramsView = Collections.unmodifiableCollection(params.values());
            private final Cls parent;

            private Method(Cls parent, String original, String desc, String mapped, Map<String, String> metadata) {
                super(original, mapped, metadata);
                this.desc = desc;
                this.parent = parent;
            }

            @Override
            public String getDescriptor() {
                return desc;
            }

            @Override
            public String getMappedDescriptor() {
                return getParent().getParent().remapDescriptor(desc);
            }

            @Override
            public Collection<Parameter> getParameters() {
                return paramsView;
            }

            private Parameter addParameter(int index, String original, String mapped, Map<String, String> metadata) {
                return retPut(params, index, new Parameter(this, index, original, mapped, metadata));
            }

            @Override
            public String remapParameter(int index, String name) {
                Parameter param = params.get(index);
                return param == null ? name : param.getMapped();
            }

            @Override
            public String write(Format format, boolean reversed) {
                String oName = !reversed ? getOriginal() : getMapped();
                String mName = !reversed ? getMapped() : getOriginal();
                String oOwner = !reversed ? parent.getOriginal() : parent.getMapped();
                String mOwner = !reversed ? parent.getMapped() : parent.getOriginal();
                String oDesc = !reversed ? getDescriptor() : getMappedDescriptor();
                String mDesc = !reversed ? getMappedDescriptor() : getDescriptor();

                switch (format) {
                    case SRG:
                    case XSRG:
                        return "MD: " + oOwner + '/' + oName + ' ' + oDesc + ' ' + mOwner + '/' + mName + ' ' + mDesc;
                    case CSRG:
                        return oOwner + ' ' + oName + ' ' + oDesc + ' ' + mName;
                    case TSRG:
                    case TSRG2:
                        return '\t' + oName + ' ' + oDesc + ' ' + mName;
                    case TINY1:
                        return "METHOD\t" + oOwner + '\t' + oDesc + '\t' + oName + '\t' + mName;
                    case TINY:
                        return "\tm\t" + oDesc + '\t' + oName + '\t' + mName;
                    case PG:
                        int start = Integer.parseInt(getMetadata().getOrDefault("start_line", "0"));
                        int end = Integer.parseInt(getMetadata().getOrDefault("end_line", "0"));
                        return "    " + (start == 0 && end == 0 ? "" : start + ":" + end + ":") + InternalUtils.toSource(oName, oDesc) + " -> " + mName;
                    default:
                        throw new UnsupportedOperationException("Unknown format: " + format);
                }
            }

            @Override
            public String toString() {
                return write(Format.SRG, false);
            }

            @Override
            public Cls getParent() {
                return parent;
            }

            static class Parameter extends Node implements IParameter {
                private final int index;
                private final Method parent;

                Parameter(Method parent, int index, String original, String mapped, Map<String, String> metadata) {
                    super(original, mapped, metadata);
                    this.index = index;
                    this.parent = parent;
                }

                @Override
                public IMethod getParent() {
                    return parent;
                }

                @Override
                public int getIndex() {
                    return index;
                }

                @Override
                public String write(Format format, boolean reversed) {
                    String oName = !reversed ? getOriginal() : getMapped();
                    String mName = !reversed ? getMapped() : getOriginal();
                    switch (format) {
                        case SRG:
                        case XSRG:
                        case CSRG:
                        case TSRG:
                        case PG:
                        case TINY1:
                            return null;
                        case TINY:
                            return "\t\tp\t" + getIndex() + '\t' + oName + '\t' + mName;
                        case TSRG2:
                            return "\t\t" + getIndex() + ' ' + oName + ' ' + mName;
                        default:
                            throw new UnsupportedOperationException("Unknown format: " + format);
                    }
                }
            }
        }
    }
}
