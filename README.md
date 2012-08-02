# Katello.auto GUI automation 

## Usage

There are several options for running automation.  You'll need to download [the latest
binary](https://github.com/downloads/RedHatQE/katello.auto/katello.auto-1.0.0-SNAPSHOT-standalone.jar).

### Running pre-packaged tests from command line
Run 

    $ java -jar katello.auto-1.0.0-SNAPSHOT-standalone.jar -m katello.tests.suite -h

to get a list of command line options (the `-m katello.tests.suite` is required directly after the jar filename).

An example command line would be 

    $ java -jar katello.auto-1.0.0-SNAPSHOT-standalone.jar -m katello.tests.suite -s https://my.host/katello katello.tests.suite/katello-tests

where the last argument is the test
group to run.  Existing test groups are

     katello.tests.suite/katello-tests
     katello.tests.suite/sam-tests
     katello.tests.login/login-tests
     katello.tests.navigation/nav-tests
     katello.tests.organizations/org-tests
     katello.tests.environments/environment-tests
     katello.tests.providers/provider-tests
     katello.tests.promotions/promotion-tests
     katello.tests.permissions/permission-tests
     katello.tests.systems/system-tests
     katello.tests.sync_management/sync-tests
     katello.tests.users/user-tests
     katello.tests.e2e/end-to-end-tests

By default this will start up 3 firefox browsers and run the specified
tests in parallel.

### Viewing results

After the tests finish running, there will be a file testng-report.xml in the current directory.  You can either view this file directly in an editor or browser, or if you prefer a nicely formatted HTML report, [Jenkins](http://jenkins-ci.org/) with the TestNG plugin can generate one for you.  The internal Red Hat QE Jenkins server has a [job set up to display the result](https://url.corp.redhat.com/e82371c) - just upload the xml file. 

If you are running your own Jenkins server, this job is very easy to set up.  Just add a file parameter `testng-report.xml`, and then check the box `Publish TestNG Results` and fill in `*.xml`.

### Running arbitrary commands

You can connect to an interactive prompt in several different ways.
If you don't have one of the IDE's handy, you can use the plain repl
as follows:

    $ java -jar katello.auto-1.0.0-SNAPSHOT-standalone.jar
    Clojure 1.4.0
    user=> (load "bootstrap")

That will open a single browser and leave you back at the prompt with
the ability to run arbitrary tests and commands.  See the [API
Documentation](http://RedHatQE.github.com/katello.auto/) for
details, especially the
[katello.ui-tasks](http://RedHatQE.github.com/katello.auto/katello.ui-tasks-api.html)
namespace.  All those tasks can be accessed directly from the prompt,
for example

    user=> (create-organization "foo") 
    
Will create an organization.

### Connecting with an IDE

If you want paren matching/editing, syntax highlighting, and a bunch
of other IDE features, you can start the automation as a server and
connect to it with an IDE.

    $ java -jar katello.auto-1.0.0-SNAPSHOT-standalone.jar -m swank.swank 4005 

will start the server on port 4005 (which is generally what the IDEs
connect to by default).

#### Emacs

See [Emacs-clojure](https://github.com/RedHatQE/emacs-clojure) for a
nice emacs init file that has everything you need already set up.
Unfortunately it requires emacs24, which comes with Fedora 17.  But emacs24
can be built easily.

Start emacs (the first startup will download and built some emacs
packages), and then run `M-x slime-connect` and specify the port you
used above.  Then type `(load "bootstrap")` to start the browser.

#### Eclipse

Currently not compatible with running interactively with Katello GUI
automation.  Eclipse only supports nREPL protocol, not swank.  nREPL
lib will be added to the binary soon to enable eclipse to communicate
with it. 

#### vim

Install [http://www.vim.org/scripts/script.php?script_id=2531](SLIMV),
type `,c` to connect to whatever port you started the swank server on.
