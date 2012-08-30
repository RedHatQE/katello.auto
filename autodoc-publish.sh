#!/bin/bash

rm -rf doc/
git clone git@github.com:RedHatQE/katello.auto.git doc

cd doc
git symbolic-ref HEAD refs/heads/gh-pages
rm .git/index
git clean -fdx
cd ..

lein doc 

cd doc
git add -A
git commit -m "Documentation update"
git push --force origin gh-pages
