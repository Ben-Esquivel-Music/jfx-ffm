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

Use the following commands to run demos build in the previous section, passing
the same `-Djavafx.home=<JAVAFX>` used to build:

Code Area Demo: `ant run-codearea-demo`

Notebook Demo: `ant run-notebook-demo`

Rich Editor Demo: `ant run-richeditor-demo`

RichTextArea Tester: `ant run-richtextarea-demo`

