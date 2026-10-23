# IBM Toolkit format evidence

`TCPIPClient_Mapping.map` is copied without changes from
[ot4i/tcpip-input-tutorial](https://github.com/ot4i/tcpip-input-tutorial/blob/1dd375a2dbe78ac00cafcc440daee3551a01d5f8/src/TCPIPClient/TCPIPClient_Mapping.map),
commit `1dd375a2dbe78ac00cafcc440daee3551a01d5f8`, MIT license (full text in
the [repository NOTICE](../../../../../../NOTICE)).
The repository is an IBM Integration Bus Toolkit tutorial published by Open
Technologies for Integration. It establishes the native `mappingRoot`,
`mappingDeclaration`, `mb:msg(...)`, assembly variables and `move` syntax.
It intentionally fails the supported-subset check because it uses `foreach`,
`override` and shared library schema references.

`../CustomerMove.map` is a new, reduced fixture using that native XML grammar,
with local schema references and scalar `move` operations. It is not claimed
to be an unmodified Toolkit export or to establish full IIB compatibility.
The supported profile is CCL namespace `http://www.ibm.com/2008/ccl/Mapping`,
version `8.0.5.0`, XMLNSC assembly, local XSD, one mapping declaration,
same-type scalar moves. Conditions, casts, assign, convert, foreach, functions,
database operations and unknown constructs produce diagnostics and no mapper.

`../MappingFlow.msgflow` is a reduced XMI fixture using the native node type
`ComIbmMSLMapping.msgnode:FCMComposite_1` and native
`mappingExpression="msl://{default}#CustomerMove"` reference syntax, confirmed in
[the tutorial's TCPIPReceiver.msgflow](https://github.com/ot4i/tcpip-input-tutorial/blob/1dd375a2dbe78ac00cafcc440daee3551a01d5f8/src/TCPIPClient/TCPIPReceiver.msgflow).

Runtime profile: XML body strings enter a generated Spring processor, which
uses original XSD element names and namespace metadata to read typed JavaBeans,
calls the generated MapStruct implementation, and serializes the target root.
The profile requires one unchanged `Properties` to `Properties` move and retains
Camel/JMS headers. It does not model IBM's complete message assembly or MQMD.
Unknown XML fields/attributes, duplicate fields, wrong namespaces, absent
required fields, invalid scalar lexical forms and external entities fail before
the body is replaced. XSD sequence order is enforced. Optional null fields are
omitted; required nillable null fields use `xsi:nil="true"`.
This is a bounded structural/type implementation, not complete XSD validation:
restrictions, facets, choices, repeated fields and other unsupported schema
constructs are rejected during conversion.
