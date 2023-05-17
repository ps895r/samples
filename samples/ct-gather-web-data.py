#!/usr/bin/env python3

"""
Copyright 2019, 2020, 2021 HPED LP All Rights Reserved.

##############################################################

    Continuous Test - HPED LP

    Gather data from live systems, scraped Ansible data, test results, etc.
    Create a JSON dump of the most recent data for each system. This data is
    meant to be easily ingested by the web data API.

##############################################################
"""

import argparse
import datetime as dt
import json
import os


import ct_gatherer
import ct_lock
import ct_logger


def main(flags):
    machine_data = []
    # Keep track of failures as we go and notify at the end. Don't want to fail
    # to provide any data due to one bad machine.
    failures = []
    for system in flags.systems:
        machine_data.append({"name": system})
        for plugin in ct_gatherer.get_plugins():
            try:
                machine_data[-1][plugin] = ct_gatherer.gather(plugin, system)
            except Exception:
                ct_logger.log(
                    f"Failed to gather machine_data for {system}",
                    level=0,
                )
                failures.append(f"{plugin}:{system}")
    if failures:
        ct_logger.log("web data choked on:", ", ".join(failures))
    # Might want to add additional categories of data later. Also, Flask
    # prefers to format dicts over lists
    data = {
        "machines": machine_data,
        "gatherer": {
            "timestamp": dt.datetime.now(dt.timezone.utc).timestamp()
        }
    }
    if flags.debug:
        print(json.dumps(data, indent=4, sort_keys=True))
    else:
        dump_web_data(data)
    return


def dump_web_data(data):
    out_dir = "/root/ct-data/web-data/"
    os.makedirs(out_dir, exist_ok=True)
    timestamp = dt.datetime.now().strftime("%Y%m%d%H%M%S")
    try:
        for filename in ["latest.json", f"{timestamp}.json"]:
            print("wrote", filename)
            with open(os.path.join(out_dir, filename), "w") as handle:
                json.dump(data, handle, indent=4, sort_keys=True)
    except Exception:
        ct_logger.log(
            f"dump_web_data: Failure for {filename}",
            level=0,
        )
    return


def get_flags():
    parser = argparse.ArgumentParser(
        description="gather data for the CT web API"
    )
    parser.add_argument(
        "systems",
        nargs="*",
        help="only scrape data for these systems",
        default=get_systems(),
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="write to stdout, enable verbose logging",
    )
    flags = parser.parse_args()
    if flags.debug:
        ct_logger.set_verbosity(1)
    return flags


def get_systems():
    with open("/usr/etc/machines.json", "r") as handle:
        return json.load(handle)


if __name__ == "__main__":
    # Make sure we enable verbose logging before locking, if applicable
    flags = get_flags()
    name = "ct-gather-web-data"
    with ct_lock.Lock(name):
        main(flags)
