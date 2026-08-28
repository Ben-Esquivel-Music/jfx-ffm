# Rich Text Area Demos

This project contains a number of applications that use the new RichTextArea and CodeArea controls,
for the purposes of demonstration of capabilities as well as testing.


- [RichEditorDemoApp.java](src/com/oracle/demo/richtext/editor/RichEditorDemoApp.java)
  is an example of a simple standalone rich text editor that uses the new RichTextArea control.
- [RichTextAreaDemoApp.java](src/com/oracle/demo/richtext/rta/RichTextAreaDemoApp.java)
  provides a demo application primarily for testing of the RichTextArea behavior.
- [CodeAreaDemoApp.java](src/com/oracle/demo/richtext/codearea/CodeAreaDemoApp.java)
  provides a demo application primarily for testing of the CodeArea behavior.
- [NotebookMockupApp.java](src/com/oracle/demo/richtext/notebook/NotebookMockupApp.java)
  provides an example of a GUI for an interactive notebook application.
  

## Building

### Using Eclipse IDE

Import and run the project.



### Using Command Line

Execute the `ant` command in this directory, pointing `javafx.home` at a
JavaFX SDK using an absolute path:
```
ant -Djavafx.home=<JAVAFX>
```

Building this repository with `mvn install` produces a suitable SDK in
`sdk/target/sdk` at the repository root, which is also the default when
building in place.



## Running Demos

Use the following commands to run the demos built in the previous section. Ant
does not retain properties between invocations, so pass the same
`-Djavafx.home=<JAVAFX>` used to build; omitting it silently falls back to the
in-tree default:

Code Area Demo: `ant -Djavafx.home=<JAVAFX> run-codearea-demo`

Notebook Demo: `ant -Djavafx.home=<JAVAFX> run-notebook-demo`

Rich Editor Demo: `ant -Djavafx.home=<JAVAFX> run-richeditor-demo`

RichTextArea Tester: `ant -Djavafx.home=<JAVAFX> run-richtextarea-demo`

