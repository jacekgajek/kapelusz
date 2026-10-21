# Band settlement app

Build a fully functional, CRM-style yet simple app for band settlements, named "Art Split"

The goal of an app is to simplify distribution of money between band members.

As a logic base, see the Rozliczenia.ods file.


# Features

## Event creation/edition

The main entity which is the source of most of data is a concert or performence -- more general, "Event".

For the UX current (recently added) event edition and creation must the most easy to access thing.

Usually few people get the money and need to distribute it fairly among other members.

In an event creation/edition screen, it must be possible to

- choose any subset of members from a predefined pool – not all members of the team must participate in given event
  Make UX easy and intuitive; usually the full set is small (eg 6), and selected eg 4 of artists.
  Add option to add an ad-hoc person outside from the predefined pool (like a guest artist)
- It must be possible to input incurred costs by each band member - like fuel or train tickets.
- It must be possible to input where the money currently sits. For example, member A collected money from a hat and has all of it, another collected money from ticket service
- For each event, a share of payout can be defined (eg the leader/composer might want more). By default it's 100% for every body, which means an even split (see the spreadsheet)


The final result of settlement is a situation where money which currently sits at people is equal to their payout share plus return of all costs.
So first of all, from the global pool of income, all members who incurred a cost get their cost refunded, then the rest is split between all people.


## Overview screen 

List of events which is also the starting/home screen

- name, date, their status (whether everything was settled or not)

## Admin screen (accessible via cog wheel in mobile app)

- Edit predefined band members, and what view can they see
  - viewer - read-only, can see everything
  - member - can declare costs, view settlements and costs of others as well as all financial information but only in events it participated.
  - lead - can edit create events, add ad-hoc people, be a money-holder and settle pay-outs
  - admin - can edit predefined team member, assign permission to members and invite other app users to assign them to defined band members (see below)
           It cannot remove admin status in a way there is no admin left in the group.
  - owner - user who created the group. It has admin rights and no admin can remove admin rights from it
Users can only see views/settings which they have permission for

## "Zero" screen

A user who is not assigned to a band (a fresh account) can create a band and automatically becomes it's owner.
When it creates a band member, it can invite another app user to be that member, by using a timed invite code.
Another app user has option to "Join Band", where it has to input the code, given by admin outside of app.
If it matches, the user is assigned to chosen by admin/lead band member

## Roles

Make it easy to access features depending on a role. Every user can select what kind of view they want to see

- "Declare costs" as a regular band member, I want to just input all my incurred costs, optionally including photo proofs and description. I am only interested in managing a list of my costs.
- as a money-holder, I want to distribute money between members.
  Make it as easy as clicking a button "Settle" which is displayed next to each band member who participated in a given event. That means that money was transferred (outside app) and it's settled
  Add a "Settle All" button to mark everything as settled – which basically means that the "where money currently sits" is automatically filled with the expected final state (see above)


# Tech stack

## App

Create a kotlin multiplatform project using the Jetbrains Compose framework, with web and android targets

All logic and state must be possible to edit locally, with immediate synchronization to a server state stored in an AWS S3 bucket.
Don't add complicated conflict resolution – the latest synchronization wins.

You can create a bucket by adding a resource here: /home/jacek/IdeaProjects/my-aws/terraform/s3.tf

Manage users by oath via google. Once authorized, a user can create a new band or be invited by in-app notification to existing band.

